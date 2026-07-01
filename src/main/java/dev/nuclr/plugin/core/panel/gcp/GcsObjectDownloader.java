package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
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

    /**
     * Downloads {@code gs://bucket/key} into {@code destination}, overwriting it. Tries the
     * bucket's regional endpoint first and falls back to the global one; refreshes the access
     * token once and retries on a 401.
     */
    Result downloadToFile(String bucket, String key, Path destination) {
        String regional = GcsEndpoints.host(bucket);
        List<String> hosts = regional.equals(GcsEndpoints.GLOBAL_HOST)
                ? List.of(GcsEndpoints.GLOBAL_HOST)
                : List.of(regional, GcsEndpoints.GLOBAL_HOST);

        Result last = new Result.Err(new GcpError.CommandFailed("no endpoint tried"));
        for (String host : hosts) {
            last = attempt(host, bucket, key, destination);
            if (last instanceof Result.Ok) {
                return last;
            }
        }
        return last;
    }

    /** One host, with a single token-refresh retry on 401. */
    private Result attempt(String host, String bucket, String key, Path destination) {
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
                // ofFile streams the body straight to disk and returns the connection to the pool,
                // so back-to-back downloads reuse the warm TLS session.
                HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(
                        destination, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                long elapsedMs = millisSince(startNanos);
                int status = response.statusCode();
                if (status == 200) {
                    log.info("GCS GET gs://{}/{} via {}: {} bytes in {} ms", bucket, key, host, sizeOf(destination), elapsedMs);
                    return new Result.Ok();
                }
                String detail = snippet(destination);
                if (status == 401 && tokenAttempt == 0) {
                    log.info("GCS GET gs://{}/{} via {}: 401 after {} ms, refreshing token", bucket, key, host, elapsedMs);
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                log.warn("GCS GET gs://{}/{} via {}: HTTP {} in {} ms", bucket, key, host, status, elapsedMs);
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

    /** Read a short, bounded snippet of an error body that ofFile wrote to {@code file}. */
    private static String snippet(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int len = Math.min(bytes.length, 1024);
            return new String(bytes, 0, len, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
