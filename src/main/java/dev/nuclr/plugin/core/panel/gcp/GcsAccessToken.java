package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.List;

/**
 * Supplies an OAuth2 access token for the GCS REST API, obtained once via
 * {@code gcloud auth print-access-token} and cached until shortly before it expires.
 *
 * <p>Shelling out to gcloud costs ~1s of Python startup, so doing it per download is what made
 * quick view slow. Tokens are valid ~1 hour; caching them means subsequent downloads are plain
 * HTTPS GETs with no gcloud process at all.
 */
final class GcsAccessToken {

    /** Refresh well before the ~1h expiry to avoid races with token expiration. */
    private static final long TTL_MS = 50 * 60_000L;

    private static final GcloudCli CLI = new GcloudCli();

    private static String token;
    private static long expiresAtMs;

    private GcsAccessToken() {}

    /**
     * Returns a usable access token, fetching a fresh one if the cache is empty/stale or
     * {@code forceRefresh} is set (e.g. after a 401).
     */
    static synchronized String get(boolean forceRefresh) throws IOException {
        long now = System.currentTimeMillis();
        if (!forceRefresh && token != null && now < expiresAtMs) {
            return token;
        }
        token = fetch();
        expiresAtMs = now + TTL_MS;
        return token;
    }

    /** Drop the cached token so the next {@link #get} re-fetches. */
    static synchronized void invalidate() {
        token = null;
        expiresAtMs = 0L;
    }

    private static String fetch() throws IOException {
        try {
            GcloudCli.CliResult result = CLI.execute(List.of("auth", "print-access-token"));
            if (result.exitCode() != 0) {
                throw new IOException("gcloud auth print-access-token failed: " + result.stderr().strip());
            }
            String value = result.stdout().strip();
            if (value.isEmpty()) {
                throw new IOException("gcloud returned an empty access token");
            }
            return value;
        } catch (GcloudCli.GcloudNotFoundException e) {
            throw new IOException("gcloud CLI not found", e);
        } catch (GcloudCli.GcloudTimeoutException e) {
            throw new IOException("gcloud timed out fetching an access token", e);
        }
    }
}
