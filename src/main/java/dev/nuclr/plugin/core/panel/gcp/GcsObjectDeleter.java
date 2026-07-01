package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Deletes a Cloud Storage object via the GCS JSON API ({@code DELETE .../o/{key}}) using a cached
 * {@link GcsAccessToken}. Mirrors {@link GcsObjectDownloader}: it prefers the bucket's regional
 * endpoint over the global host and refreshes the token once on a 401. Avoids
 * {@code gcloud storage rm} (whose ~5s Python startup dominates a per-object delete).
 */
@Slf4j
final class GcsObjectDeleter {

    /** Typed outcome of {@link #delete}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok() implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Deletes {@code gs://bucket/key}. A 404 is treated as success (already gone). */
    Result delete(String bucket, String key) {
        String regional = GcsEndpoints.host(bucket);
        List<String> hosts = regional.equals(GcsEndpoints.GLOBAL_HOST)
                ? List.of(GcsEndpoints.GLOBAL_HOST)
                : List.of(regional, GcsEndpoints.GLOBAL_HOST);

        Result last = new Result.Err(new GcpError.CommandFailed("no endpoint tried"));
        for (String host : hosts) {
            last = attempt(host, bucket, key);
            if (last instanceof Result.Ok) {
                return last;
            }
        }
        return last;
    }

    /** One host, with a single token-refresh retry on 401. */
    private Result attempt(String host, String bucket, String key) {
        for (int tokenAttempt = 0; tokenAttempt < 2; tokenAttempt++) {
            String token;
            try {
                token = GcsAccessToken.get(tokenAttempt == 1);
            } catch (IOException e) {
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            HttpRequest request = HttpRequest.newBuilder(objectUri(host, bucket, key))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build();
            long startNanos = System.nanoTime();
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = millisSince(startNanos);
                int status = response.statusCode();
                if (status == 204 || status == 200 || status == 404) {
                    log.info("GCS DELETE gs://{}/{} via {}: {} in {} ms", bucket, key, host, status, elapsedMs);
                    return new Result.Ok();
                }
                if (status == 401 && tokenAttempt == 0) {
                    log.info("GCS DELETE gs://{}/{} via {}: 401 after {} ms, refreshing token", bucket, key, host, elapsedMs);
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                log.warn("GCS DELETE gs://{}/{} via {}: HTTP {} in {} ms", bucket, key, host, status, elapsedMs);
                return new Result.Err(classify(status, response.body()));
            } catch (IOException e) {
                log.warn("GCS DELETE gs://{}/{} via {} failed after {} ms: {}", bucket, key, host, millisSince(startNanos), e.getMessage());
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result.Err(new GcpError.CommandFailed("Interrupted while deleting"));
            }
        }
        return new Result.Err(new GcpError.NotAuthenticated());
    }

    /** {@code https://<host>/storage/v1/b/{bucket}/o/{key}} (key fully encoded). */
    private static URI objectUri(String host, String bucket, String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("https://" + host + "/storage/v1/b/" + bucket + "/o/" + encodedKey);
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
