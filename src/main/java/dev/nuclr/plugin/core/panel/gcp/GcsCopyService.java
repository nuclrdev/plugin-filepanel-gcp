package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
        GcsProgressDialog.run("Copy", callback ->
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
            cb.onStart("Copying " + object.getName() + " (" + (i + 1) + "/" + objects.size() + ")");

            Path target = destination.resolve(object.getName());
            boolean append = false;

            if (Files.exists(target)) {
                Path dir = destination;
                Predicate<String> existsInDir = n -> Files.exists(dir.resolve(n));
                // "Ask" (null mode) prompts per clash; any other mode was pre-chosen in the setup dialog.
                Action action = existingMode;
                String renameName = null;
                if (action == null) {
                    Resolution resolution = conflictDialog.resolve(
                            target.toString(),
                            metaText(object, "Size") + "   " + metaText(object, "Updated"), // New = source object
                            GcsCopyConflictDialog.pathDetail(target),                        // Existing = local file
                            GcsCopyConflictDialog.autoRenameName(object.getName(), existsInDir));
                    action = resolution.action();
                    renameName = resolution.renameName();
                }
                if (action == Action.CANCEL) {
                    log.info("GCS copy cancelled by user after {} file(s)", copied);
                    break;
                }
                if (action == Action.SKIP) {
                    continue;
                }
                if (action == Action.RENAME) {
                    String name = renameName != null
                            ? renameName
                            : GcsCopyConflictDialog.autoRenameName(object.getName(), existsInDir);
                    target = dir.resolve(name);
                    // A user-typed / pre-chosen name can still collide; auto-bump it once more.
                    if (Files.exists(target)) {
                        target = dir.resolve(GcsCopyConflictDialog.autoRenameName(name, existsInDir));
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

    // -------------------------------------------------------------------------
    // Accept copy (upload): local (or other-plugin) files -> the open bucket listing
    // -------------------------------------------------------------------------

    /**
     * Accept a copy initiated in the other panel: upload the selected source files into the bucket
     * listing currently open here ({@code currentResource}). Shows the setup, "File already exists"
     * and progress dialogs like the FS plugin, then refreshes this panel ({@code destUuid}).
     *
     * @param existingByName display-name → resource for the entries already in this listing (for the
     *                       duplicate check and the conflict dialog's "Existing" row)
     */
    void acceptCopy(List<NuclrResource> selectedResources, NuclrResource focusedResource,
            NuclrResource currentResource, NuclrPluginContext context, String destUuid,
            Map<String, NuclrResource> existingByName) {

        if (!GcpResource.isBucket(currentResource) && !GcpResource.isObjectDir(currentResource)) {
            showError("Open a bucket to copy files into it.");
            return;
        }
        String bucket = GcpResource.bucketName(currentResource);
        String prefix = GcpResource.objectPrefix(currentResource);

        List<NuclrResource> sources = collectFiles(selectedResources, focusedResource);
        if (sources.isEmpty()) {
            showError("Nothing to copy here (folders are not uploaded).");
            return;
        }

        GcsCopyDialog.Upload options = GcsCopyDialog.showUpload(header(sources), "gs://" + bucket + "/" + prefix);
        if (options == null) {
            return; // cancelled at the setup dialog
        }

        Map<String, NuclrResource> existing = existingByName != null ? existingByName : Map.of();
        var failures = new ArrayList<String>();
        int[] uploaded = { 0 };
        GcsProgressDialog.run("Copy", callback ->
                uploaded[0] = runUpload(sources, bucket, prefix, options.existing(), existing, callback, failures));

        if (uploaded[0] > 0) {
            // New objects landed in the open listing: drop its cache and reload this panel.
            GcpDiskCache.clearObjects(bucket, prefix);
            if (context != null && context.getEventBus() != null) {
                context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", destUuid), null);
            }
        }
        if (!failures.isEmpty()) {
            showError("Some files could not be copied:\n" + String.join("\n", failures));
        }
    }

    private int runUpload(List<NuclrResource> sources, String bucket, String prefix, Action existingMode,
            Map<String, NuclrResource> existingByName, NuclrPluginCallback cb, List<String> failures) {

        var conflictDialog = new GcsCopyConflictDialog();
        var uploader = new GcsObjectUploader();
        Set<String> taken = new HashSet<>(existingByName.keySet());
        int uploaded = 0;

        for (int i = 0; i < sources.size(); i++) {
            if (cb.isCancelled()) {
                break;
            }
            NuclrResource source = sources.get(i);
            String name = source.getName();
            cb.onStart("Copying " + name + " (" + (i + 1) + "/" + sources.size() + ")");

            Predicate<String> existsAtDest = taken::contains;
            if (existsAtDest.test(name)) {
                Action action = existingMode;
                String renameName = null;
                if (action == null) {
                    NuclrResource existing = existingByName.get(name);
                    Resolution resolution = conflictDialog.resolve(
                            "gs://" + bucket + "/" + prefix + name,
                            sourceDetail(source),          // New = incoming local file
                            existingDetail(existing),      // Existing = object already in the bucket
                            GcsCopyConflictDialog.autoRenameName(name, existsAtDest));
                    action = resolution.action();
                    renameName = resolution.renameName();
                }
                if (action == Action.CANCEL) {
                    log.info("GCS upload cancelled by user after {} file(s)", uploaded);
                    break;
                }
                if (action == Action.SKIP) {
                    continue;
                }
                if (action == Action.RENAME) {
                    name = renameName != null
                            ? renameName
                            : GcsCopyConflictDialog.autoRenameName(name, existsAtDest);
                    if (existsAtDest.test(name)) {
                        name = GcsCopyConflictDialog.autoRenameName(name, existsAtDest);
                    }
                }
                // Cloud Storage objects cannot be appended to; Append and Overwrite both replace.
            }

            switch (uploadOne(uploader, source, bucket, prefix + name, cb)) {
                case OK -> {
                    uploaded++;
                    taken.add(name);
                }
                case CANCELLED -> {
                    log.info("GCS upload cancelled by user after {} file(s)", uploaded);
                    return uploaded;
                }
                case FAILED -> failures.add(source.getName());
            }
        }
        log.info("GCS upload complete: {} file(s) uploaded, {} failed", uploaded, failures.size());
        return uploaded;
    }

    /** Upload one source resource's content to {@code gs://bucket/key}, streaming with progress. */
    private Outcome uploadOne(GcsObjectUploader uploader, NuclrResource source, String bucket, String key,
            NuclrPluginCallback cb) {
        GcsObjectUploader.BodySource body = () -> {
            try {
                return source.openInputStream();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
        GcsObjectDownloader.Result result = uploader.upload(
                bucket, key, body, sourceSize(source), cb::onProgress, cb::isCancelled);
        if (result instanceof GcsObjectDownloader.Result.Err err) {
            if (err.error() instanceof GcpError.Cancelled) {
                return Outcome.CANCELLED;
            }
            log.warn("GCS upload of {} failed: {}", source.getName(), err.error());
            return Outcome.FAILED;
        }
        return Outcome.OK;
    }

    /** Copyable (non-folder) source files: marked selection if present, else the cursor item; ".." skipped. */
    private static List<NuclrResource> collectFiles(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
        List<NuclrResource> chosen = new ArrayList<>();
        if (selectedResources != null && !selectedResources.isEmpty()) {
            chosen.addAll(selectedResources);
        } else if (focusedResource != null) {
            chosen.add(focusedResource);
        }

        List<NuclrResource> files = new ArrayList<>();
        for (NuclrResource resource : chosen) {
            if (resource != null && !resource.isFolder() && !"..".equals(resource.getName())) {
                files.add(resource);
            }
        }
        return files;
    }

    /** Best-effort byte size of a source: the file size when it has a local path, else its length field. */
    private static long sourceSize(NuclrResource source) {
        Path path = source.getPath();
        if (path != null) {
            try {
                return Files.size(path);
            } catch (IOException ignored) {
                // fall through to the resource's declared length
            }
        }
        return Math.max(source.getLength(), 0);
    }

    /** "New" row detail for an incoming source: local size + timestamp, or just its length. */
    private static String sourceDetail(NuclrResource source) {
        Path path = source.getPath();
        return path != null ? GcsCopyConflictDialog.pathDetail(path) : String.valueOf(Math.max(source.getLength(), 0));
    }

    /** "Existing" row detail for an object already in the bucket, from its listing metadata. */
    private static String existingDetail(NuclrResource existing) {
        if (existing == null) {
            return "";
        }
        return metaText(existing, "Size") + "   " + metaText(existing, "Updated");
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
