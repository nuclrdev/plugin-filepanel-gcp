package dev.nuclr.plugin.core.panel.gcp;

import java.util.Locale;

/** Shared classification of gcloud failures from their stderr text. No Swing, no I/O. */
public final class GcloudErrors {

    private GcloudErrors() {}

    /**
     * Heuristically detects authentication / permission errors from gcloud's stderr.
     * Covers unauthenticated, expired tokens, missing active account, and access denial.
     */
    static boolean isAuthError(String stderr) {
        String lower = stderr.toLowerCase(Locale.ROOT);
        return lower.contains("auth login")
                || lower.contains("not currently have an active account")
                || lower.contains("invalid_grant")
                || lower.contains("token has been expired")
                || lower.contains("do not have permission")
                || (lower.contains("please run") && lower.contains("gcloud auth"));
    }

    /** Classifies a non-zero gcloud exit into a {@link GcpError}, truncating noisy stderr. */
    public static GcpError classify(String stderr) {
        String text = stderr == null ? "" : stderr.strip();
        if (isAuthError(text)) {
            return new GcpError.NotAuthenticated();
        }
        String truncated = text.length() > 500 ? text.substring(0, 500) : text;
        return new GcpError.CommandFailed(truncated);
    }
}
