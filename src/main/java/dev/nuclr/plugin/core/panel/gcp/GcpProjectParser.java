package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the JSON output of {@code gcloud projects list --format=json} into typed model objects.
 * Pure function — no I/O, no Swing.
 */
public class GcpProjectParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a JSON array produced by {@code gcloud projects list --format=json}.
     *
     * @param json raw stdout from gcloud
     * @return immutable list of projects; empty if the JSON array is empty or cannot be parsed
     * @throws IOException if the input is not valid JSON
     */
    public List<GcpProject> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var projects = new ArrayList<GcpProject>();
        for (JsonNode node : root) {
            String projectId = text(node, "projectId");
            if (projectId.isBlank()) {
                continue;
            }
            projects.add(new GcpProject(
                    projectId,
                    text(node, "name"),
                    node.path("projectNumber").asLong(0L),
                    text(node, "lifecycleState")));
        }
        return Collections.unmodifiableList(projects);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
