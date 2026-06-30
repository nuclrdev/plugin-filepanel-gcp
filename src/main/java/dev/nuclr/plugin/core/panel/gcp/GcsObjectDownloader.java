package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Copies a Cloud Storage object to a local file via {@code gcloud storage cp}. The object is
 * streamed straight to disk (never buffered in memory), so even a large file can be downloaded
 * for the host's quick-view providers to render. No Swing.
 */
final class GcsObjectDownloader {

    /** Typed outcome of {@link #downloadToFile}. */
    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok() implements Result {}
        record Err(GcpError error) implements Result {}
    }

    private static final int TIMEOUT_SECONDS = 120;

    /**
     * Downloads {@code gs://bucket/key} into {@code destination}, overwriting it.
     *
     * @return {@link Result.Ok} on success, or {@link Result.Err} with a classified error
     */
    Result downloadToFile(String bucket, String key, Path destination) {
        String url = "gs://" + bucket + "/" + key;
        Process process;
        try {
            process = new ProcessBuilder(List.of(
                    GcloudCli.executable(), "storage", "cp", url, destination.toString())).start();
        } catch (IOException e) {
            return new Result.Err(new GcpError.GcloudNotFound());
        }

        // Drain both streams so a chatty gcloud can't block on a full pipe.
        var stderrRef = new AtomicReference<String>("");
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try {
                stderrRef.set(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        Thread stdoutDrain = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().readAllBytes();
            } catch (IOException ignored) {
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result.Err(new GcpError.CommandFailed("Interrupted while downloading"));
        }
        if (!finished) {
            process.destroyForcibly();
            return new Result.Err(new GcpError.Timeout());
        }

        joinQuietly(stderrDrain);
        joinQuietly(stdoutDrain);

        if (process.exitValue() != 0) {
            return new Result.Err(GcloudErrors.classify(stderrRef.get()));
        }
        return new Result.Ok();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
