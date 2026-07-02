package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * F7 "Make Folder" for the GCP panel. Prompts for a name and, mirroring the local file-system
 * plugin's {@code MakeNewFolderService}, rejects blank names, path separators, and a name that
 * already exists in the current listing — then creates a Cloud Storage "folder" as a zero-byte
 * placeholder object (see {@link GcsFolderCreator}).
 */
@Slf4j
public final class GcsMakeFolderService {

    private static final String DIALOG_TITLE = "Make Folder";

    private final GcsFolderCreator creator = new GcsFolderCreator();

    /**
     * Prompt for a folder name and create it under {@code gs://bucket/prefix}. {@code existingNames}
     * are the display names already present in the current listing (for the duplicate check).
     *
     * @return the new folder's display name (its own segment) on success, or {@code null} otherwise
     */
    public String makeFolder(String bucket, String prefix, Set<String> existingNames) {

        String folderName = promptFolderName();
        if (folderName == null) {
            return null; // cancelled
        }
        folderName = folderName.trim();
        if (folderName.isBlank()) {
            return null;
        }
        if (isInvalidSingleFolderName(folderName)) {
            showError("Folder name cannot contain path separators.");
            return null;
        }
        if (existingNames != null
                && (existingNames.contains(folderName) || existingNames.contains(folderName + "/"))) {
            showError("A file or folder with that name already exists.");
            return null;
        }

        String folderKey = prefix + folderName + "/";
        GcsFolderCreator.Result result = creator.createFolder(bucket, folderKey);
        if (result instanceof GcsFolderCreator.Result.Err err) {
            log.warn("Failed to create folder gs://{}/{}: {}", bucket, folderKey, err.error());
            GcpErrorDialog.show(err.error());
            return null;
        }
        return folderName;
    }

    /** Matches the FS plugin's single-segment name check: no {@code . / \ NUL} and not {@code .}/{@code ..}. */
    private static boolean isInvalidSingleFolderName(String folderName) {
        return folderName.equals(".")
                || folderName.equals("..")
                || folderName.indexOf('/') >= 0
                || folderName.indexOf('\\') >= 0
                || folderName.indexOf('\0') >= 0;
    }

    private static String promptFolderName() {
        final String[] result = new String[1];
        runOnEdtAndWait(() -> result[0] = JOptionPane.showInputDialog(
                null, "Folder name:", DIALOG_TITLE, JOptionPane.PLAIN_MESSAGE));
        return result[0];
    }

    private static void showError(String message) {
        runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, DIALOG_TITLE, JOptionPane.ERROR_MESSAGE));
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            log.warn("Failed to run make-folder dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
