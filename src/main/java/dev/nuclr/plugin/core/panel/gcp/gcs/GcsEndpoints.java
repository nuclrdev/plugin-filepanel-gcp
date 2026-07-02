package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chooses the lowest-latency GCS API host for a bucket.
 *
 * <p>For a bucket in a single region, Google's <em>regional endpoint</em>
 * ({@code storage.<region>.rep.googleapis.com}) routes straight to that region's backend and is
 * markedly faster than the global {@code storage.googleapis.com} anycast endpoint when the
 * caller is in the same region. Bucket locations are recorded as the bucket list is fetched
 * (it already carries each bucket's location), so no extra lookup is needed.
 */
public final class GcsEndpoints {

    static final String GLOBAL_HOST = "storage.googleapis.com";

    /** A GCP single region, e.g. {@code australia-southeast1}, {@code us-central1}. */
    private static final Pattern SINGLE_REGION = Pattern.compile("[a-z]+-[a-z]+\\d+");

    /** bucket name → lower-cased location (e.g. {@code australia-southeast1}, {@code us}). */
    private static final Map<String, String> LOCATION = new ConcurrentHashMap<>();

    private GcsEndpoints() {}

    /** Remember a bucket's location (from the bucket listing) for endpoint selection. */
    public static void recordLocation(String bucket, String location) {
        if (bucket == null || location == null || location.isBlank() || "-".equals(location)) {
            return;
        }
        LOCATION.put(bucket, location.toLowerCase(Locale.ROOT));
    }

    /** The preferred API host for a bucket: its regional endpoint if known, else the global one. */
    static String host(String bucket) {
        String location = LOCATION.get(bucket);
        if (location != null && SINGLE_REGION.matcher(location).matches()) {
            return "storage." + location + ".rep.googleapis.com";
        }
        return GLOBAL_HOST;
    }

    public static void clear() {
        LOCATION.clear();
    }
}
