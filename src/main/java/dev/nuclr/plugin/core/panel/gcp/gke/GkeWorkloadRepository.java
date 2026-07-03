package dev.nuclr.plugin.core.panel.gcp.gke;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lists a project's GKE workloads (Deployments) by, for each cluster, running
 * {@code gcloud container clusters get-credentials} (which points kubectl at that cluster and updates
 * the user's kubeconfig) and then {@code kubectl get deployments --all-namespaces -o json}. Parses the
 * results into typed, display-ready records. Owns all error classification. No Swing.
 *
 * <p>Best-effort across clusters: a cluster that fails to authenticate or query is skipped rather than
 * failing the whole listing. A hard error (kubectl missing, or every cluster failing with nothing to
 * show) is returned as {@code Err}; "no workloads" is an empty {@code Ok}.
 */
public class GkeWorkloadRepository {

    /** Typed outcome of {@link #listWorkloads(String, List)}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(List<GkeWorkload> workloads) implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private final GcloudCli gcloud = new GcloudCli();
    private final KubectlCli kubectl = new KubectlCli();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lists the Deployment workloads across the given project's {@code clusters}. */
    public Result listWorkloads(String projectId, List<GkeCluster> clusters) {
        var workloads = new ArrayList<GkeWorkload>();
        GcpError firstError = null;

        for (GkeCluster cluster : clusters) {
            // Point kubectl at this cluster (writes the user's kubeconfig / current-context).
            Object creds = runGcloud(List.of(
                    "container", "clusters", "get-credentials", cluster.name(),
                    "--location=" + cluster.location(), "--project=" + projectId));
            if (creds instanceof GcpError error) {
                firstError = firstError == null ? error : firstError;
                continue;
            }

            KubectlCli.CliResult result;
            try {
                result = kubectl.execute(List.of("get", "deployments", "--all-namespaces", "-o", "json"));
            } catch (KubectlCli.KubectlNotFoundException e) {
                // kubectl missing is a hard, project-wide failure — no cluster can be queried.
                return new Result.Err(new GcpError.CommandFailed(
                        "kubectl not found on PATH. Install it (e.g. gcloud components install kubectl) to list workloads."));
            } catch (KubectlCli.KubectlTimeoutException e) {
                firstError = firstError == null ? new GcpError.Timeout() : firstError;
                continue;
            } catch (IOException e) {
                firstError = firstError == null ? new GcpError.CommandFailed(e.getMessage()) : firstError;
                continue;
            }

            if (result.exitCode() != 0) {
                firstError = firstError == null ? GcloudErrors.classify(result.stderr()) : firstError;
                continue;
            }

            try {
                parseDeployments(result.stdout(), cluster, workloads);
            } catch (IOException e) {
                firstError = firstError == null
                        ? new GcpError.CommandFailed("Failed to parse kubectl output: " + e.getMessage()) : firstError;
            }
        }

        // Nothing to show and at least one cluster errored: surface the first error. Otherwise the
        // (possibly empty) list is a success — no clusters, or clusters with no deployments.
        if (workloads.isEmpty() && firstError != null) {
            return new Result.Err(firstError);
        }
        return new Result.Ok(List.copyOf(workloads));
    }

    /** Runs a gcloud command, returning the stdout {@link String} on success or a {@link GcpError} on failure. */
    private Object runGcloud(List<String> args) {
        GcloudCli.CliResult result;
        try {
            result = gcloud.execute(args);
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

    private void parseDeployments(String json, GkeCluster cluster, List<GkeWorkload> out) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            JsonNode metadata = item.path("metadata");
            String name = text(metadata, "name");
            if (name.isBlank()) {
                continue;
            }
            String namespace = dash(text(metadata, "namespace"));
            int desired = item.path("spec").path("replicas").asInt(0);
            int ready = item.path("status").path("readyReplicas").asInt(0);
            out.add(new GkeWorkload(
                    name, "Deployment", namespace, cluster.name(), cluster.location(), ready + "/" + desired));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
