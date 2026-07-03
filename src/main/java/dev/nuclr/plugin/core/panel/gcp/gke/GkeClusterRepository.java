package dev.nuclr.plugin.core.panel.gcp.gke;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Orchestrates {@link GcloudCli} to list a project's GKE clusters ({@code gcloud container clusters
 * list}) and parse the JSON into typed, display-ready records. Owns all error classification. No Swing.
 *
 * <p>Mirrors {@link dev.nuclr.plugin.core.panel.gcp.pubsub.GcpPubsubRepository}: a successful run with
 * no results is an empty {@code Ok}, not an error.
 */
public class GkeClusterRepository {

    /** Typed outcome of {@link #listClusters(String)}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(List<GkeCluster> clusters) implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private final GcloudCli cli = new GcloudCli();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lists the GKE clusters in the given project. */
    public Result listClusters(String projectId) {
        Object outcome = run(List.of("container", "clusters", "list", "--project=" + projectId, "--format=json"));
        if (outcome instanceof GcpError error) {
            return new Result.Err(error);
        }
        try {
            return new Result.Ok(parseClusters((String) outcome));
        } catch (IOException e) {
            return new Result.Err(new GcpError.CommandFailed("Failed to parse gcloud output: " + e.getMessage()));
        }
    }

    /** Runs the command, returning the stdout {@link String} on success or a {@link GcpError} on failure. */
    private Object run(List<String> args) {
        GcloudCli.CliResult result;
        try {
            result = cli.execute(args);
        } catch (GcloudCli.GcloudNotFoundException e) {
            return new GcpError.GcloudNotFound();
        } catch (GcloudCli.GcloudTimeoutException e) {
            return new GcpError.Timeout();
        } catch (IOException e) {
            return new GcpError.CommandFailed(e.getMessage());
        }
        if (result.exitCode() != 0) {
            return GcloudErrors.classify(result.stderr());
        }
        return result.stdout();
    }

    private List<GkeCluster> parseClusters(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var clusters = new ArrayList<GkeCluster>();
        for (JsonNode node : root) {
            String name = text(node, "name");
            if (name.isBlank()) {
                continue;
            }
            clusters.add(new GkeCluster(
                    name,
                    dash(text(node, "location")),
                    dash(text(node, "status")),
                    dash(text(node, "currentMasterVersion")),
                    dash(text(node, "currentNodeCount"))));
        }
        return List.copyOf(clusters);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
