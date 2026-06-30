package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks the local temp files that GCS object resources download themselves into (see
 * {@link GcpResource#openInputStream}). Each file is marked {@code deleteOnExit} as a safety
 * net and removed eagerly when the panel plugin unloads.
 */
@Slf4j
final class GcsTempFiles {

    private static final Set<Path> FILES = ConcurrentHashMap.newKeySet();

    private GcsTempFiles() {}

    /** Records a materialized temp file and schedules it for deletion at JVM exit. */
    static void register(Path file) {
        FILES.add(file);
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
    }
}
