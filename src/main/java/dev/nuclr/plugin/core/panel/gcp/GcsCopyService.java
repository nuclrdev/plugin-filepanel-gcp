package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.gcp.GcsCopyConflictDialog.Action;
import dev.nuclr.plugin.core.panel.gcp.GcsCopyConflictDialog.Resolution;
import lombok.extern.slf4j.Slf4j;

/**
 * Performs the F5 copy from the GCP panel: each selected Cloud Storage object is downloaded and
 * written into the <em>other</em> panel's current folder. Because the destination is another
 * plugin's folder we can only write when that folder is a local filesystem directory (the common
 * case — copying a bucket object down to disk); other destinations are reported as unsupported.
 *
 * <p>Name clashes are resolved through {@link GcsCopyConflictDialog} (Overwrite / Skip / Rename /
 * Append / Cancel, with "Remember choice"), matching the local file-system plugin's behaviour. The
 * whole run happens off the EDT; when it finishes the destination panel is asked to refresh.
 */
@Slf4j
final class GcsCopyService {

    private final GcsObjectDownloader downloader = new GcsObjectDownloader();

    /**
     * Copy the selected (or focused) GCS objects into the folder currently open in {@code other}.
     * Shows the F5 "Copy" setup dialog, then a modal, cancellable progress dialog, blocking until
     * the copy finishes or is cancelled. Each object's mark in the source panel ({@code sourceUuid})
     * is cleared the moment it is copied; the destination panel is refreshed at the end.
     */
    void copy(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource,
            NuclrPluginContext context, String sourceUuid) {

        Path defaultDestination = destinationDir(other);
        if (defaultDestination == null) {
            showError("Copy is only supported to a local folder.");
            return;
        }

        List<NuclrResource> objects = collectObjects(selectedResources, focusedResource);
        if (objects.isEmpty()) {
            showError("Select one or more objects to copy (folders are not copied).");
            return;
        }

        GcsCopyDialog.Options options = GcsCopyDialog.show(header(objects), defaultDestination);
        if (options == null) {
            return; // cancelled at the setup dialog
        }
        Path destination = options.destination();
        if (!Files.isDirectory(destination)) {
            showError("The destination is not a folder:\n" + destination);
            return;
        }

        var failures = new ArrayList<String>();
        GcsCopyProgressDialog.run(callback ->
                run(objects, destination, options.existing(), callback, failures, context, sourceUuid));

        refreshDestination(other, context);
        if (!failures.isEmpty()) {
            showError("Some objects could not be copied:\n" + String.join("\n", failures));
        }
    }

    private void run(List<NuclrResource> objects, Path destination, Action existingMode, NuclrPluginCallback cb,
            List<String> failures, NuclrPluginContext context, String sourceUuid) {

        var conflictDialog = new GcsCopyConflictDialog();
        int copied = 0;

        for (int i = 0; i < objects.size(); i++) {
            if (cb.isCancelled()) {
                break;
            }
            NuclrResource object = objects.get(i);
            cb.onStart(object.getName() + " (" + (i + 1) + "/" + objects.size() + ")");

            Path target = destination.resolve(object.getName());
            boolean append = false;

            if (Files.exists(target)) {
                // "Ask" (null mode) prompts per clash; any other mode was pre-chosen in the setup dialog.
                Action action = existingMode;
                Path renameTarget = null;
                if (action == null) {
                    Resolution resolution = conflictDialog.resolve(
                            metaText(object, "Size"), metaText(object, "Updated"), target);
                    action = resolution.action();
                    renameTarget = resolution.renameTarget();
                }
                if (action == Action.CANCEL) {
                    log.info("GCS copy cancelled by user after {} file(s)", copied);
                    break;
                }
                if (action == Action.SKIP) {
                    continue;
                }
                if (action == Action.RENAME) {
                    target = renameTarget != null ? renameTarget : GcsCopyConflictDialog.autoRename(target);
                    // A remembered / pre-chosen auto-rename can still collide on a later file.
                    if (Files.exists(target)) {
                        target = GcsCopyConflictDialog.autoRename(target);
                    }
                }
                append = action == Action.APPEND;
            }

            switch (downloadInto(object, target, append, cb)) {
                case OK -> {
                    copied++;
                    unmark(context, sourceUuid, object); // deselect this file now that it is copied
                }
                case CANCELLED -> {
                    log.info("GCS copy cancelled by user after {} file(s)", copied);
                    return;
                }
                case FAILED -> failures.add(object.getName());
            }
        }

        log.info("GCS copy complete: {} copied, {} failed", copied, failures.size());
    }

    private enum Outcome { OK, CANCELLED, FAILED }

    /** Download {@code object} to a temp file next to {@code target}, then move (or append) into place. */
    private Outcome downloadInto(NuclrResource object, Path target, boolean append, NuclrPluginCallback cb) {
        String bucket = GcpResource.bucketName(object);
        String key = GcpResource.objectKey(object);
        if (bucket == null || key == null) {
            log.warn("GCS copy of {} skipped: not a downloadable object", object.getName());
            return Outcome.FAILED;
        }

        Path dir = target.getParent();
        Path temp;
        try {
            temp = Files.createTempFile(dir != null ? dir : target, "nuclr-gcs-copy-", ".part");
        } catch (IOException e) {
            log.warn("GCS copy of {} failed to create temp file: {}", object.getName(), e.getMessage());
            return Outcome.FAILED;
        }

        try {
            GcsObjectDownloader.Result result = downloader.downloadToFile(
                    bucket, key, temp, cb::onProgress, cb::isCancelled);
            if (result instanceof GcsObjectDownloader.Result.Err err) {
                if (err.error() instanceof GcpError.Cancelled) {
                    return Outcome.CANCELLED;
                }
                log.warn("GCS copy of {} failed: {}", object.getName(), err.error());
                return Outcome.FAILED;
            }
            if (append) {
                try (var in = Files.newInputStream(temp)) {
                    Files.write(target, in.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } else {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Outcome.OK;
        } catch (IOException e) {
            log.warn("GCS copy of {} failed: {}", object.getName(), e.getMessage());
            return Outcome.FAILED;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.debug("Could not delete temp file {}: {}", temp, e.getMessage());
            }
        }
    }

    /** The other panel's current folder as a local directory, or {@code null} if it is not one. */
    private static Path destinationDir(BaseNuclrPlugin other) {
        if (other == null) {
            return null;
        }
        NuclrResource current = other.getCurrentResource();
        Path path = current != null ? current.getPath() : null;
        return path != null && Files.isDirectory(path) ? path : null;
    }

    /** Copyable objects from the selection (marked if any, else the cursor item); folders and ".." skipped. */
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

    private void refreshDestination(BaseNuclrPlugin other, NuclrPluginContext context) {
        if (other != null && context != null && context.getEventBus() != null) {
            context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", other.uuid()), null);
        }
    }

    /** Ask the host to clear this object's mark in the source panel (see Events.FilePanelUnmarkEntry). */
    private static void unmark(NuclrPluginContext context, String sourceUuid, NuclrResource object) {
        if (context == null || context.getEventBus() == null || sourceUuid == null || object.getUuid() == null) {
            return;
        }
        context.getEventBus().emit("filepanel.unmark.entry",
                Map.of("plugin.uuid", sourceUuid, "entry.uuid", object.getUuid()), null);
    }

    /** Short description of the copy set for the setup dialog: the single name, or "N items". */
    private static String header(List<NuclrResource> objects) {
        return objects.size() == 1 ? objects.get(0).getName() : objects.size() + " items";
    }

    private static String metaText(NuclrResource resource, String key) {
        Object value = resource.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private static void showError(String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(null, message, "Copy", JOptionPane.ERROR_MESSAGE));
    }
}
