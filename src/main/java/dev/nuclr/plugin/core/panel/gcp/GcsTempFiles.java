package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks the local temp files that GCS object resources download themselves into (see
 * {@link GcpResource#openInputStream}). Files are also cached by their {@code gs://bucket/key}
 * so re-viewing the same object — even after the listing was rebuilt — is instant. Each file is
 * marked {@code deleteOnExit} as a safety net and removed eagerly when the panel plugin unloads.
 */
@Slf4j
final class GcsTempFiles {

    private static final Set<Path> FILES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Path> BY_OBJECT = new ConcurrentHashMap<>();

    private GcsTempFiles() {}

    /** The temp file already downloaded for {@code gsKey}, or {@code null} if not cached/gone. */
    static Path cached(String gsKey) {
        Path file = BY_OBJECT.get(gsKey);
        if (file != null && Files.exists(file)) {
            return file;
        }
        if (file != null) {
            BY_OBJECT.remove(gsKey, file); // stale entry; will be re-downloaded
        }
        return null;
    }

    /** Records a materialized temp file under its object key and schedules it for deletion. */
    static void register(String gsKey, Path file) {
        FILES.add(file);
        BY_OBJECT.put(gsKey, file);
        file.toFile().deleteOnExit();
    }

    /** Deletes every tracked temp file now (best-effort) and clears the registry. */
    static void cleanup() {
        for (Path file : FILES) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete GCS temp file {}: {}", file, e.getMessage());
            }
        }
        FILES.clear();
        BY_OBJECT.clear();
    }
}
