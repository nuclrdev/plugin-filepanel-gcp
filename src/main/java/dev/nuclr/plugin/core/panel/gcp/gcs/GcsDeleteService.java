package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Performs the F8 delete from the GCP panel: the selected (or focused) Cloud Storage objects are
 * removed from their bucket via {@link GcsObjectDeleter}. A confirmation is shown first (see
 * {@link GcsDeleteDialogs}); the deletions then run under a modal, cancellable progress dialog.
 *
 * <p>Folders (object prefixes) and the {@code ..} entry are never deleted — only leaf objects.
 */
@Slf4j
public final class GcsDeleteService {

    private final GcsObjectDeleter deleter = new GcsObjectDeleter();

    /**
     * Confirm and delete the selected (or focused) objects. Blocks until the operation finishes or
     * is cancelled. Returns the number of objects actually deleted (0 if nothing was confirmed).
     */
    public int delete(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

        List<NuclrResource> objects = collectObjects(selectedResources, focusedResource);
        if (objects.isEmpty()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Select one or more objects to delete (folders are not deleted).",
                    "Delete", JOptionPane.INFORMATION_MESSAGE));
            return 0;
        }

        if (!GcsDeleteDialogs.confirmDelete(objects)) {
            return 0;
        }

        int[] deleted = { 0 };
        GcsProgressDialog.run("Delete", callback -> deleted[0] = run(objects, callback));
        return deleted[0];
    }

    private int run(List<NuclrResource> objects, NuclrPluginCallback cb) {

        int deleted = 0;
        for (int i = 0; i < objects.size(); i++) {
            if (cb.isCancelled()) {
                break;
            }
            NuclrResource object = objects.get(i);
            cb.onStart("Deleting " + object.getName() + " (" + (i + 1) + "/" + objects.size() + ")");
            cb.onProgress(i, objects.size());

            String bucket = GcpResource.bucketName(object);
            String key = GcpResource.objectKey(object);
            if (bucket == null || key == null) {
                continue;
            }

            GcsObjectDeleter.Result result = deleter.delete(bucket, key);
            if (result instanceof GcsObjectDeleter.Result.Err err) {
                // Prompt to skip the rest or abort, matching the FS delete's per-item error handling.
                if (!GcsDeleteDialogs.error(object.getName(), err.error())) {
                    log.info("GCS delete aborted by user after {} object(s)", deleted);
                    break;
                }
                continue;
            }
            deleted++;
        }
        cb.onProgress(objects.size(), objects.size());
        log.info("GCS delete complete: {} object(s) deleted", deleted);
        return deleted;
    }

    /** Deletable objects from the selection (marked if any, else the cursor item); folders and ".." skipped. */
    private static List<NuclrResource> collectObjects(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
        List<NuclrResource> chosen = new ArrayList<>();
        if (selectedResources != null && !selectedResources.isEmpty()) {
            chosen.addAll(selectedResources);
        } else if (focusedResource != null) {
            chosen.add(focusedResource);
        }

        List<NuclrResource> objects = new ArrayList<>();
        for (NuclrResource resource : chosen) {
            if (GcpResource.isObject(resource)) {
                objects.add(resource);
            }
        }
        return objects;
    }
}
