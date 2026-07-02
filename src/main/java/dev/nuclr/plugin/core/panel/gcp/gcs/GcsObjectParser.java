package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps a single array element of {@code gcloud storage ls --json} output to a {@link GcsObject}.
 * Each element is either a sub-prefix ({@code "type":"prefix"}) or an object
 * ({@code "type":"cloud_object"} with a nested {@code metadata} block). Pure functions.
 */
final class GcsObjectParser {

    private static final DateTimeFormatter DISPLAY_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private GcsObjectParser() {}

    /**
     * Converts one listing element to a {@link GcsObject} whose name is relative to
     * {@code parentPrefix} (the object key prefix being listed, e.g. {@code "logs/"} or
     * {@code ""} at the bucket root). Returns {@code null} for elements that cannot be
     * interpreted (e.g. the bucket's own placeholder entry).
     */
    static GcsObject fromNode(JsonNode node, String parentPrefix) {
        String type = node.path("type").asText("");

        if ("prefix".equals(type)) {
            String key = keyFromUrl(node.path("url").asText(""));
            String name = relative(key, parentPrefix);
            return name.isEmpty() ? null : new GcsObject(name, true, "-", "-", "-");
        }

        // cloud_object
        JsonNode meta = node.path("metadata");
        String key = meta.path("name").asText("");
        String name = relative(key, parentPrefix);
        if (name.isEmpty()) {
            return null; // the prefix placeholder object (key == parentPrefix); skip it
        }
        return new GcsObject(
                name,
                false,
                formatSize(meta.path("size").asText("")),
                blankToDash(meta.path("storageClass").asText("")),
                formatTimestamp(meta.path("updated").asText("")));
    }

    /** The object key of a {@code gs://bucket/key} URL (without the {@code #generation} suffix). */
    private static String keyFromUrl(String url) {
        String s = url.startsWith("gs://") ? url.substring(5) : url;
        int slash = s.indexOf('/');
        String key = slash < 0 ? "" : s.substring(slash + 1);
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /** Strips the listed prefix so only the immediate child segment remains. */
    private static String relative(String key, String parentPrefix) {
        if (parentPrefix != null && !parentPrefix.isEmpty() && key.startsWith(parentPrefix)) {
            return key.substring(parentPrefix.length());
        }
        return key;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Human-readable byte size (e.g. {@code 8.1 KB}); dash when unknown. */
    private static String formatSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        long bytes;
        try {
            bytes = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }

    /** Reformats an RFC 3339 timestamp to {@code yyyy-MM-dd HH:mm:ss}, falling back to raw. */
    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        try {
            return OffsetDateTime.parse(raw).format(DISPLAY_TS);
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
