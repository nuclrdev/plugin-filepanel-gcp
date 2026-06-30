package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Downloads a Cloud Storage object to a local file via the GCS JSON API
 * ({@code GET .../o/{key}?alt=media}) using a cached {@link GcsAccessToken}.
 *
 * <p>This deliberately avoids {@code gcloud storage cp}, whose per-invocation Python startup
 * (~5s) dominated quick-view latency. A warm download here is a single HTTPS GET (~0.4s),
 * streamed straight to disk so memory stays bounded. No Swing.
 */
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

    /**
     * Downloads {@code gs://bucket/key} into {@code destination}, overwriting it. Refreshes the
     * access token once and retries on a 401.
     */
    Result downloadToFile(String bucket, String key, Path destination) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token;
            try {
                token = GcsAccessToken.get(attempt == 1);
            } catch (IOException e) {
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            }

            HttpRequest request = HttpRequest.newBuilder(mediaUri(bucket, key))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 200) {
                    try (InputStream body = response.body()) {
                        Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return new Result.Ok();
                }

                String detail = drain(response.body());
                if (status == 401 && attempt == 0) {
                    GcsAccessToken.invalidate();
                    continue; // token expired mid-session; refresh and retry once
                }
                return new Result.Err(classify(status, detail));
            } catch (IOException e) {
                return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result.Err(new GcpError.CommandFailed("Interrupted while downloading"));
            }
        }
        return new Result.Err(new GcpError.NotAuthenticated());
    }

    /** {@code https://storage.googleapis.com/storage/v1/b/{bucket}/o/{key}?alt=media} (key fully encoded). */
    private static URI mediaUri(String bucket, String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("https://storage.googleapis.com/storage/v1/b/" + bucket + "/o/" + encodedKey + "?alt=media");
    }

    private static GcpError classify(int status, String body) {
        return switch (status) {
            case 401, 403 -> new GcpError.NotAuthenticated();
            case 404 -> new GcpError.CommandFailed("Object not found (404).");
            default -> new GcpError.CommandFailed("HTTP " + status + (body.isBlank() ? "" : ": " + body));
        };
    }

    /** Read a short, bounded snippet of an error body for diagnostics. */
    private static String drain(InputStream in) {
        try (in) {
            byte[] bytes = in.readNBytes(1024);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
