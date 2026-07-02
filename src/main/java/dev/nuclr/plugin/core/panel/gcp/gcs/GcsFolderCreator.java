package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

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
 * Creates a Cloud Storage "folder" by inserting a zero-byte placeholder object whose key ends in
 * {@code /} (e.g. {@code gs://bucket/logs/2026/}), the same convention the Cloud Console uses. Runs
 * through the JSON API upload endpoint ({@code POST /upload/storage/v1/b/{bucket}/o?uploadType=media})
 * with a cached {@link GcsAccessToken}, mirroring {@link GcsObjectDeleter}: it prefers the bucket's
 * regional endpoint over the global host and refreshes the token once on a 401.
 */
@Slf4j
final class GcsFolderCreator {

    /** Typed outcome of {@link #createFolder}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok() implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Creates the placeholder object {@code gs://bucket/folderKey} (folderKey must end in {@code /}). */
    Result createFolder(String bucket, String folderKey) {
        String regional = GcsEndpoints.host(bucket);
        List<String> hosts = regional.equals(GcsEndpoints.GLOBAL_HOST)
                ? List.of(GcsEndpoints.GLOBAL_HOST)
                : List.of(regional, GcsEndpoints.GLOBAL_HOST);

        Result last = new Result.Err(new GcpError.CommandFailed("no endpoint tried"));
        for (String host : hosts) {
            last = attempt(host, bucket, folderKey);
            if (last instanceof Result.Ok) {
                return last;
            }
        }
        return last;
    }

    /** One host, with a single token-refresh retry on 401. */
    private Result attempt(String host, String bucket, String folderKey) {
        for (int tokenAttempt = 0; tokenAttempt < 2; tokenAttempt++) {
            String token;
            try {
                token = GcsAccessToken.get(tokenAttempt == 1);
            } catch (IOException e) {
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            HttpRequest request = HttpRequest.newBuilder(uploadUri(host, bucket, folderKey))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            long startNanos = System.nanoTime();
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = millisSince(startNanos);
                int status = response.statusCode();
                if (status == 200) {
                    log.info("GCS create folder gs://{}/{} via {}: {} ms", bucket, folderKey, host, elapsedMs);
                    return new Result.Ok();
                }
                if (status == 401 && tokenAttempt == 0) {
                    log.info("GCS create folder gs://{}/{} via {}: 401 after {} ms, refreshing token", bucket, folderKey, host, elapsedMs);
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                log.warn("GCS create folder gs://{}/{} via {}: HTTP {} in {} ms", bucket, folderKey, host, status, elapsedMs);
                return new Result.Err(classify(status, response.body()));
            } catch (IOException e) {
                log.warn("GCS create folder gs://{}/{} via {} failed after {} ms: {}", bucket, folderKey, host, millisSince(startNanos), e.getMessage());
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result.Err(new GcpError.CommandFailed("Interrupted while creating folder"));
            }
        }
        return new Result.Err(new GcpError.NotAuthenticated());
    }

    /** {@code https://<host>/upload/storage/v1/b/{bucket}/o?uploadType=media&name=<key>} (key fully encoded). */
    private static URI uploadUri(String host, String bucket, String folderKey) {
        String encodedKey = URLEncoder.encode(folderKey, StandardCharsets.UTF_8).replace("+", "%20");
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
