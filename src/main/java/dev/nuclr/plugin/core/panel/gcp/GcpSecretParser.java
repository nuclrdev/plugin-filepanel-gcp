package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the JSON output of {@code gcloud secrets list --format=json} into typed, display-ready
 * {@link GcpSecret} objects. Pure function — no I/O, no Swing.
 *
 * <p>Each element carries a full resource {@code name} ({@code projects/<num>/secrets/<id>}); only
 * the trailing secret id is shown. The {@code replication} block is summarised as either
 * {@code automatic} or the list of user-managed replica locations.
 */
public class GcpSecretParser {

    private static final DateTimeFormatter DISPLAY_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a JSON array produced by {@code gcloud secrets list --format=json}.
     *
     * @param json raw stdout from gcloud
     * @return immutable list of secrets; empty if the JSON array is empty
     * @throws IOException if the input is not valid JSON
     */
    public List<GcpSecret> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var secrets = new ArrayList<GcpSecret>();
        for (JsonNode node : root) {
            String name = shortName(text(node, "name"));
            if (name.isBlank()) {
                continue;
            }
            secrets.add(new GcpSecret(
                    name,
                    formatTimestamp(text(node, "createTime")),
                    replication(node.path("replication"))));
        }
        return Collections.unmodifiableList(secrets);
    }

    /** {@code projects/123/secrets/my-secret} → {@code my-secret}. */
    private static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        int slash = fullName.lastIndexOf('/');
        return slash < 0 ? fullName : fullName.substring(slash + 1);
    }

    /** {@code automatic}, or a comma-separated list of user-managed replica locations. */
    private static String replication(JsonNode replication) {
        if (replication == null || replication.isMissingNode() || replication.isNull()) {
            return "-";
        }
        if (!replication.path("automatic").isMissingNode()) {
            return "automatic";
        }
        JsonNode replicas = replication.path("userManaged").path("replicas");
        if (replicas.isArray() && !replicas.isEmpty()) {
            var locations = new ArrayList<String>();
            for (JsonNode replica : replicas) {
                String location = text(replica, "location");
                if (!location.isBlank()) {
                    locations.add(location);
                }
            }
            if (!locations.isEmpty()) {
                return String.join(", ", locations);
            }
        }
        return "-";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    /** Reformats an RFC 3339 timestamp to {@code yyyy-MM-dd HH:mm:ss}, falling back to the raw value. */
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
