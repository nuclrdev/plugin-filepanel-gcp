package dev.nuclr.plugin.core.panel.gcp.secret;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.io.IOException;
import java.util.List;

/**
 * Orchestrates {@link GcloudCli} and {@link GcpSecretParser} to list the Secret Manager secrets of
 * a project. Owns all error classification. No Swing.
 *
 * <p>Mirrors {@link GcsBucketRepository}: a successful run with no secrets is an empty
 * {@link Result.Ok}, not an error.
 */
public class GcpSecretRepository {

    /** Typed outcome of {@link #listSecrets(String)}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(List<GcpSecret> secrets) implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private final GcloudCli cli = new GcloudCli();
    private final GcpSecretParser parser = new GcpSecretParser();

    /**
     * Lists the Secret Manager secrets in the given project.
     *
     * @param projectId the GCP project to query
     * @return {@link Result.Ok} with the secret list (possibly empty), or
     *         {@link Result.Err} with a classified error
     */
    public Result listSecrets(String projectId) {
        GcloudCli.CliResult cliResult;
        try {
            cliResult = cli.execute(List.of(
                    "secrets", "list", "--project=" + projectId, "--format=json"));
        } catch (GcloudCli.GcloudNotFoundException e) {
            return new Result.Err(new GcpError.GcloudNotFound());
        } catch (GcloudCli.GcloudTimeoutException e) {
            return new Result.Err(new GcpError.Timeout());
        } catch (IOException e) {
            return new Result.Err(new GcpError.CommandFailed(e.getMessage()));
        }

        if (cliResult.exitCode() != 0) {
            return new Result.Err(GcloudErrors.classify(cliResult.stderr()));
        }

        try {
            return new Result.Ok(parser.parse(cliResult.stdout()));
        } catch (IOException e) {
            return new Result.Err(
                    new GcpError.CommandFailed("Failed to parse gcloud output: " + e.getMessage()));
        }
    }
}
