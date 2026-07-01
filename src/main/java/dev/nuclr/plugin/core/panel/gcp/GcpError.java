package dev.nuclr.plugin.core.panel.gcp;

public sealed interface GcpError
        permits GcpError.GcloudNotFound, GcpError.NotAuthenticated,
                GcpError.NoProjectsAccessible, GcpError.CommandFailed, GcpError.Timeout,
                GcpError.Cancelled {

    /** gcloud binary is not on PATH. */
    record GcloudNotFound() implements GcpError {}

    /** Authenticated account is absent or credentials are expired. */
    record NotAuthenticated() implements GcpError {}

    /** Authentication succeeded but the account has no accessible projects. */
    record NoProjectsAccessible() implements GcpError {}

    /** gcloud exited non-zero with an unrecognized stderr payload (truncated to 500 chars). */
    record CommandFailed(String stderr) implements GcpError {}

    /** gcloud process did not complete within the timeout window. */
    record Timeout() implements GcpError {}

    /** The operation was cancelled by the user (e.g. Cancel pressed during a copy). */
    record Cancelled() implements GcpError {}
}
