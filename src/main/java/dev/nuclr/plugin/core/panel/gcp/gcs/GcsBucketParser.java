package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the JSON output of {@code gcloud storage buckets list --format=json} into
 * typed, display-ready {@link GcsBucket} objects. Pure function — no I/O, no Swing.
 */
public class GcsBucketParser {

    // gcloud emits timestamps like "2026-01-22T11:03:03+0000" (no colon in the offset).
    private static final DateTimeFormatter GCLOUD_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final DateTimeFormatter DISPLAY_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a JSON array produced by {@code gcloud storage buckets list --format=json}.
     *
     * @param json raw stdout from gcloud
     * @return immutable list of buckets; empty if the JSON array is empty
     * @throws IOException if the input is not valid JSON
     */
    public List<GcsBucket> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var buckets = new ArrayList<GcsBucket>();
        for (JsonNode node : root) {
            String name = text(node, "name");
            if (name.isBlank()) {
                continue;
            }
            buckets.add(new GcsBucket(
                    name,
                    formatTimestamp(text(node, "creation_time")),
                    dash(text(node, "location_type")),
                    dash(text(node, "location")),
                    dash(text(node, "default_storage_class")),
                    formatTimestamp(text(node, "update_time")),
                    publicAccess(text(node, "public_access_prevention"))));
        }
        return Collections.unmodifiableList(buckets);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Reformats a gcloud timestamp to {@code yyyy-MM-dd HH:mm:ss}, falling back to the raw value. */
    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        try {
            return OffsetDateTime.parse(raw, GCLOUD_TS).format(DISPLAY_TS);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** Maps {@code public_access_prevention} to a human-readable label for the panel. */
    private static String publicAccess(String prevention) {
        return switch (prevention == null ? "" : prevention.toLowerCase()) {
            case "enforced" -> "Not public";
            case "inherited" -> "Subject to ACLs";
            case "" -> "-";
            default -> prevention;
        };
    }
}
