package dev.nuclr.plugin.core.panel.gcp;

import static dev.nuclr.plugin.core.panel.gcp.Messages.tr;

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
                tr("error.gcloud.not.found");
            case GcpError.NotAuthenticated ignored ->
                tr("error.not.authenticated");
            case GcpError.Timeout ignored ->
                tr("error.timeout");
            case GcpError.CommandFailed f ->
                tr("error.command.failed", f.stderr());
            case GcpError.NoProjectsAccessible ignored ->
                null; // handled inline — no modal
            case GcpError.Cancelled ignored ->
                null; // user-initiated cancel — nothing to report
        };

        if (message == null) {
            return;
        }

        String finalMessage = message;
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(
                        null, finalMessage, tr("error.title"), JOptionPane.ERROR_MESSAGE));
    }
}
