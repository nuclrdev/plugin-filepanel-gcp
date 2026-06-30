package dev.nuclr.plugin.core.panel.gcp;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Static utility that surfaces {@link GcpError} conditions as Swing modal dialogs.
 * This is the only class in the plugin allowed to show Swing error UI.
 * All calls are dispatched to the EDT via {@link SwingUtilities#invokeLater}.
 *
 * <p>{@link GcpError.NoProjectsAccessible} is intentionally suppressed here —
 * that case is represented inline in the file panel as a status file, not a modal.
 */
public final class GcpErrorDialog {

    private GcpErrorDialog() {}

    /**
     * Shows an error dialog for the given error, or does nothing for
     * {@link GcpError.NoProjectsAccessible} (handled inline in the panel).
     */
    public static void show(GcpError error) {
        String message = switch (error) {
            case GcpError.GcloudNotFound ignored ->
                "gcloud CLI not found. Install it and ensure it is on your PATH.";
            case GcpError.NotAuthenticated ignored ->
                "Not authenticated. Run gcloud auth login in a terminal.";
            case GcpError.Timeout ignored ->
                "gcloud timed out.";
            case GcpError.CommandFailed f ->
                "gcloud error:\n\n" + f.stderr();
            case GcpError.NoProjectsAccessible ignored ->
                null; // handled inline — no modal
        };

        if (message == null) {
            return;
        }

        String finalMessage = message;
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(
                        null, finalMessage, "GCP Plugin Error", JOptionPane.ERROR_MESSAGE));
    }
}
