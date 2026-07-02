package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Orchestrates {@link GcloudCli} to list a project's Pub/Sub topics and subscriptions and parse the
 * JSON into typed, display-ready records. Owns all error classification. No Swing.
 *
 * <p>Mirrors {@link GcpSecretRepository}: a successful run with no results is an empty
 * {@code Ok}, not an error.
 */
public class GcpPubsubRepository {

    /** Typed outcome of {@link #listTopics(String)}. */
    public sealed interface TopicResult permits TopicResult.Ok, TopicResult.Err {
        record Ok(List<GcpPubsubTopic> topics) implements TopicResult {}
        record Err(GcpError error) implements TopicResult {}
    }

    /** Typed outcome of {@link #listSubscriptions(String)}. */
    public sealed interface SubscriptionResult permits SubscriptionResult.Ok, SubscriptionResult.Err {
        record Ok(List<GcpPubsubSubscription> subscriptions) implements SubscriptionResult {}
        record Err(GcpError error) implements SubscriptionResult {}
    }

    private final GcloudCli cli = new GcloudCli();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lists the Pub/Sub topics in the given project. */
    public TopicResult listTopics(String projectId) {
        Object outcome = run(List.of("pubsub", "topics", "list", "--project=" + projectId, "--format=json"));
        if (outcome instanceof GcpError error) {
            return new TopicResult.Err(error);
        }
        try {
            return new TopicResult.Ok(parseTopics((String) outcome));
        } catch (IOException e) {
            return new TopicResult.Err(new GcpError.CommandFailed("Failed to parse gcloud output: " + e.getMessage()));
        }
    }

    /** Lists the Pub/Sub subscriptions in the given project. */
    public SubscriptionResult listSubscriptions(String projectId) {
        Object outcome = run(List.of("pubsub", "subscriptions", "list", "--project=" + projectId, "--format=json"));
        if (outcome instanceof GcpError error) {
            return new SubscriptionResult.Err(error);
        }
        try {
            return new SubscriptionResult.Ok(parseSubscriptions((String) outcome));
        } catch (IOException e) {
            return new SubscriptionResult.Err(
                    new GcpError.CommandFailed("Failed to parse gcloud output: " + e.getMessage()));
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

    private List<GcpPubsubTopic> parseTopics(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var topics = new ArrayList<GcpPubsubTopic>();
        for (JsonNode node : root) {
            String name = shortName(text(node, "name"));
            if (name.isBlank()) {
                continue;
            }
            topics.add(new GcpPubsubTopic(name, dash(text(node, "messageRetentionDuration"))));
        }
        return List.copyOf(topics);
    }

    private List<GcpPubsubSubscription> parseSubscriptions(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        var subs = new ArrayList<GcpPubsubSubscription>();
        for (JsonNode node : root) {
            String name = shortName(text(node, "name"));
            if (name.isBlank()) {
                continue;
            }
            boolean push = !node.path("pushConfig").path("pushEndpoint").asText("").isBlank();
            int ack = node.path("ackDeadlineSeconds").asInt(0);
            subs.add(new GcpPubsubSubscription(
                    name,
                    dash(shortName(text(node, "topic"))),
                    push ? "Push" : "Pull",
                    ack > 0 ? ack + "s" : "-"));
        }
        return List.copyOf(subs);
    }

    /** {@code projects/123/topics/my-topic} → {@code my-topic}. */
    private static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        int slash = fullName.lastIndexOf('/');
        return slash < 0 ? fullName : fullName.substring(slash + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
