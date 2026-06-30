package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.util.List;

/**
 * Orchestrates {@link GcloudCli} and {@link GcpProjectParser} to produce a typed result.
 * Owns all error classification. No Swing.
 */
public class GcpProjectRepository {

    /** Typed outcome of {@link #listProjects()}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(List<GcpProject> projects) implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private final GcloudCli cli = new GcloudCli();
    private final GcpProjectParser parser = new GcpProjectParser();

    /**
     * Lists all GCP projects accessible to the current gcloud account.
     *
     * @return {@link Result.Ok} with the project list, or {@link Result.Err} with a classified error
     */
    public Result listProjects() {
        GcloudCli.CliResult cliResult;
        try {
            cliResult = cli.execute(List.of("projects", "list", "--format=json"));
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

        List<GcpProject> projects;
        try {
            projects = parser.parse(cliResult.stdout());
        } catch (IOException e) {
            return new Result.Err(
                    new GcpError.CommandFailed("Failed to parse gcloud output: " + e.getMessage()));
        }

        if (projects.isEmpty()) {
            return new Result.Err(new GcpError.NoProjectsAccessible());
        }

        return new Result.Ok(projects);
    }
}
