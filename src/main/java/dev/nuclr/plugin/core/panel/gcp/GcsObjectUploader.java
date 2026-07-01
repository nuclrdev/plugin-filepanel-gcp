package dev.nuclr.plugin.core.panel.gcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Uploads a local (or otherwise streamable) file into a Cloud Storage bucket via the JSON API
 * upload endpoint ({@code POST /upload/storage/v1/b/{bucket}/o?uploadType=media}) using a cached
 * {@link GcsAccessToken}. Mirrors {@link GcsObjectDownloader}: it prefers the bucket's regional
 * endpoint over the global host, refreshes the token once on a 401, reports byte progress, and
 * aborts promptly on cancellation (returning {@link GcpError.Cancelled}).
 */
@Slf4j
final class GcsObjectUploader {

    /** Supplies a fresh stream of the source content (re-opened per attempt, e.g. on a 401 retry). */
    @FunctionalInterface
    interface BodySource {
        InputStream open() throws IOException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /** Raised from the request body stream to abort an in-flight upload on cancellation. */
    private static final class CancelledUpload extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Uploads {@code body} to {@code gs://bucket/key}. Tries the regional endpoint then the global
     * one; reports progress to {@code listener} (total is {@code size}) and stops on {@code cancelled}.
     */
    GcsObjectDownloader.Result upload(String bucket, String key, BodySource body, long size,
            GcsObjectDownloader.ProgressListener listener, BooleanSupplier cancelled) {
        String regional = GcsEndpoints.host(bucket);
        List<String> hosts = regional.equals(GcsEndpoints.GLOBAL_HOST)
                ? List.of(GcsEndpoints.GLOBAL_HOST)
                : List.of(regional, GcsEndpoints.GLOBAL_HOST);

        GcsObjectDownloader.Result last =
                new GcsObjectDownloader.Result.Err(new GcpError.CommandFailed("no endpoint tried"));
        for (String host : hosts) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return new GcsObjectDownloader.Result.Err(new GcpError.Cancelled());
            }
            last = attempt(host, bucket, key, body, size, listener, cancelled);
            if (last instanceof GcsObjectDownloader.Result.Ok
                    || last instanceof GcsObjectDownloader.Result.Err err && err.error() instanceof GcpError.Cancelled) {
                return last;
            }
        }
        return last;
    }

    /** One host, with a single token-refresh retry on 401. */
    private GcsObjectDownloader.Result attempt(String host, String bucket, String key, BodySource body, long size,
            GcsObjectDownloader.ProgressListener listener, BooleanSupplier cancelled) {
        for (int tokenAttempt = 0; tokenAttempt < 2; tokenAttempt++) {
            String token;
            try {
                token = GcsAccessToken.get(tokenAttempt == 1);
            } catch (IOException e) {
                return new GcsObjectDownloader.Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            // Open a fresh, progress-reporting stream for this attempt (a retry re-reads from the
            // start). BodyPublishers.ofInputStream's supplier cannot throw, so open it here.
            InputStream counting;
            try {
                counting = counting(body, size, listener, cancelled);
            } catch (IOException e) {
                return new GcsObjectDownloader.Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            HttpRequest request = HttpRequest.newBuilder(uploadUri(host, bucket, key))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> counting))
                    .build();
            long startNanos = System.nanoTime();
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = millisSince(startNanos);
                int status = response.statusCode();
                if (status == 200) {
                    log.info("GCS PUT gs://{}/{} via {}: {} bytes in {} ms", bucket, key, host, size, elapsedMs);
                    return new GcsObjectDownloader.Result.Ok();
                }
                if (status == 401 && tokenAttempt == 0) {
                    log.info("GCS PUT gs://{}/{} via {}: 401 after {} ms, refreshing token", bucket, key, host, elapsedMs);
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                log.warn("GCS PUT gs://{}/{} via {}: HTTP {} in {} ms", bucket, key, host, status, elapsedMs);
                return new GcsObjectDownloader.Result.Err(classify(status, response.body()));
            } catch (IOException e) {
                if (isCancellation(e)) {
                    log.info("GCS PUT gs://{}/{} via {}: cancelled after {} ms", bucket, key, host, millisSince(startNanos));
                    return new GcsObjectDownloader.Result.Err(new GcpError.Cancelled());
                }
                log.warn("GCS PUT gs://{}/{} via {} failed after {} ms: {}", bucket, key, host, millisSince(startNanos), e.getMessage());
                return new GcsObjectDownloader.Result.Err(new GcpError.CommandFailed(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new GcsObjectDownloader.Result.Err(new GcpError.CommandFailed("Interrupted while uploading"));
            }
        }
        return new GcsObjectDownloader.Result.Err(new GcpError.NotAuthenticated());
    }

    /** Wrap the source stream so each read reports progress and a cancel aborts the request. */
    private static InputStream counting(BodySource body, long total,
            GcsObjectDownloader.ProgressListener listener, BooleanSupplier cancelled) throws IOException {
        InputStream in = body.open();
        return new FilterInputStream(in) {
            private long transferred;

            @Override
            public int read() throws IOException {
                checkCancelled();
                int b = super.read();
                if (b >= 0) {
                    transferred++;
                    report();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                checkCancelled();
                int n = super.read(b, off, len);
                if (n > 0) {
                    transferred += n;
                    report();
                }
                return n;
            }

            private void checkCancelled() throws IOException {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancelledUpload();
                }
            }

            private void report() {
                if (listener != null) {
                    listener.onBytes(transferred, total);
                }
            }
        };
    }

    private static boolean isCancellation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CancelledUpload) {
                return true;
            }
        }
        return false;
    }

    /** {@code https://<host>/upload/storage/v1/b/{bucket}/o?uploadType=media&name=<key>} (key fully encoded). */
    private static URI uploadUri(String host, String bucket, String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("https://" + host + "/upload/storage/v1/b/" + bucket
                + "/o?uploadType=media&name=" + encodedKey);
    }

    private static GcpError classify(int status, String body) {
        return switch (status) {
            case 401, 403 -> new GcpError.NotAuthenticated();
            default -> new GcpError.CommandFailed("HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body.strip()));
        };
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
