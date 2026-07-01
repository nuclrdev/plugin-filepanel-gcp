package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Downloads a Cloud Storage object to a local file via the GCS JSON API
 * ({@code GET .../o/{key}?alt=media}) using a cached {@link GcsAccessToken}.
 *
 * <p>Two things keep this fast: it avoids {@code gcloud storage cp} (whose ~5s Python startup
 * dominated quick view), and it prefers the bucket's {@linkplain GcsEndpoints regional endpoint}
 * over the global anycast host (about 2× lower warm latency in-region). The shared
 * {@link HttpClient} pools connections, so back-to-back downloads reuse a warm TLS session.
 */
@Slf4j
final class GcsObjectDownloader {

    /** Typed outcome of {@link #downloadToFile}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok() implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WARM_TIMEOUT = Duration.ofSeconds(10);

    private static final int BUFFER = 64 * 1024;

    /** Receives running byte progress while a download streams; {@code total} is {@code -1} if unknown. */
    @FunctionalInterface
    interface ProgressListener {
        void onBytes(long transferred, long total);
    }

    /**
     * Downloads {@code gs://bucket/key} into {@code destination}, overwriting it. Convenience form
     * with no progress reporting and no cancellation.
     */
    Result downloadToFile(String bucket, String key, Path destination) {
        return downloadToFile(bucket, key, destination, null, null);
    }

    /**
     * Downloads {@code gs://bucket/key} into {@code destination}, overwriting it. Tries the
     * bucket's regional endpoint first and falls back to the global one; refreshes the access
     * token once and retries on a 401. Reports byte progress to {@code listener} (if non-null) and
     * aborts promptly when {@code cancelled} turns true, returning a {@link GcpError.Cancelled}.
     */
    Result downloadToFile(String bucket, String key, Path destination, ProgressListener listener, BooleanSupplier cancelled) {
        String regional = GcsEndpoints.host(bucket);
        List<String> hosts = regional.equals(GcsEndpoints.GLOBAL_HOST)
                ? List.of(GcsEndpoints.GLOBAL_HOST)
                : List.of(regional, GcsEndpoints.GLOBAL_HOST);

        Result last = new Result.Err(new GcpError.CommandFailed("no endpoint tried"));
        for (String host : hosts) {
            if (isCancelled(cancelled)) {
                return new Result.Err(new GcpError.Cancelled());
            }
            last = attempt(host, bucket, key, destination, listener, cancelled);
            if (last instanceof Result.Ok || last instanceof Result.Err err && err.error() instanceof GcpError.Cancelled) {
                return last; // success, or a user cancel we must not retry on the fallback host
            }
        }
        return last;
    }

    /** One host, with a single token-refresh retry on 401. */
    private Result attempt(String host, String bucket, String key, Path destination,
            ProgressListener listener, BooleanSupplier cancelled) {
        for (int tokenAttempt = 0; tokenAttempt < 2; tokenAttempt++) {
            String token;
            try {
                token = GcsAccessToken.get(tokenAttempt == 1);
            } catch (IOException e) {
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            HttpRequest request = HttpRequest.newBuilder(mediaUri(host, bucket, key))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            long startNanos = System.nanoTime();
            try {
                // ofInputStream lets us stream to disk ourselves so we can report byte progress and
                // stop on cancel; the connection returns to the pool once the body stream is closed.
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 200) {
                    long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    boolean cancelledMidStream = streamToFile(response.body(), destination, total, listener, cancelled);
                    long elapsedMs = millisSince(startNanos);
                    if (cancelledMidStream) {
                        log.info("GCS GET gs://{}/{} via {}: cancelled after {} ms", bucket, key, host, elapsedMs);
                        return new Result.Err(new GcpError.Cancelled());
                    }
                    log.info("GCS GET gs://{}/{} via {}: {} bytes in {} ms", bucket, key, host, sizeOf(destination), elapsedMs);
                    return new Result.Ok();
                }
                String detail = snippet(response.body());
                if (status == 401 && tokenAttempt == 0) {
                    log.info("GCS GET gs://{}/{} via {}: 401 after {} ms, refreshing token", bucket, key, host, millisSince(startNanos));
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                log.warn("GCS GET gs://{}/{} via {}: HTTP {} in {} ms", bucket, key, host, status, millisSince(startNanos));
                return new Result.Err(classify(status, detail));
            } catch (IOException e) {
                log.warn("GCS GET gs://{}/{} via {} failed after {} ms: {}", bucket, key, host, millisSince(startNanos), e.getMessage());
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result.Err(new GcpError.CommandFailed("Interrupted while downloading"));
            }
        }
        return new Result.Err(new GcpError.NotAuthenticated());
    }

    /**
     * Streams {@code body} to {@code destination}, reporting progress and checking {@code cancelled}
     * between buffers. Returns {@code true} if it stopped early because of cancellation (leaving a
     * partial file the caller is expected to discard).
     */
    private static boolean streamToFile(InputStream body, Path destination, long total,
            ProgressListener listener, BooleanSupplier cancelled) throws IOException {
        try (InputStream in = body;
                OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER];
            long transferred = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (isCancelled(cancelled)) {
                    return true;
                }
                out.write(buffer, 0, read);
                transferred += read;
                if (listener != null) {
                    listener.onBytes(transferred, total);
                }
            }
        }
        return false;
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    /**
     * Best-effort: open a TLS connection to the bucket's endpoint (and validate the token) so the
     * first real download skips the ~0.4s cold handshake. Failures are ignored.
     */
    static void warmConnection(String bucket) {
        long startNanos = System.nanoTime();
        try {
            String host = GcsEndpoints.host(bucket);
            String token = GcsAccessToken.get(false);
            URI uri = URI.create("https://" + host + "/storage/v1/b/" + bucket + "?fields=name");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(WARM_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("GCS connection to {} warmed in {} ms", host, millisSince(startNanos));
        } catch (IOException | RuntimeException e) {
            log.debug("GCS warm-up for {} failed after {} ms: {}", bucket, millisSince(startNanos), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code https://<host>/storage/v1/b/{bucket}/o/{key}?alt=media} (key fully encoded). */
    private static URI mediaUri(String host, String bucket, String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("https://" + host + "/storage/v1/b/" + bucket + "/o/" + encodedKey + "?alt=media");
    }

    private static GcpError classify(int status, String body) {
        return switch (status) {
            case 401, 403 -> new GcpError.NotAuthenticated();
            case 404 -> new GcpError.CommandFailed("Object not found (404).");
            default -> new GcpError.CommandFailed("HTTP " + status + (body.isBlank() ? "" : ": " + body));
        };
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Read a short, bounded snippet of an error response body (and drain/close it). */
    private static String snippet(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(1024);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
