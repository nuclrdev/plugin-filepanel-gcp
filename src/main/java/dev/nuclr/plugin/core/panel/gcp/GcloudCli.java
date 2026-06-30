package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes arbitrary gcloud commands and returns their raw output.
 * No parsing, no Swing — pure I/O layer.
 */
public class GcloudCli {

    private static final int TIMEOUT_SECONDS = 10;

    /**
     * Name of the gcloud executable. On Windows the CLI ships as {@code gcloud.cmd}
     * (there is no bare {@code gcloud} or {@code gcloud.exe}), and {@link ProcessBuilder}
     * does not honor {@code PATHEXT}, so the extension must be specified explicitly.
     */
    private static final String GCLOUD =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                    ? "gcloud.cmd"
                    : "gcloud";

    /** The OS-appropriate gcloud executable name, for callers that launch gcloud directly. */
    static String executable() {
        return GCLOUD;
    }

    /** Raw result of a gcloud invocation. */
    public record CliResult(String stdout, String stderr, int exitCode) {}

    /** Thrown when the gcloud binary cannot be found on PATH. */
    public static final class GcloudNotFoundException extends Exception {
        GcloudNotFoundException(Throwable cause) {
            super("gcloud CLI not found on PATH", cause);
        }
    }

    /** Thrown when gcloud does not respond within {@value #TIMEOUT_SECONDS} seconds. */
    public static final class GcloudTimeoutException extends Exception {
        GcloudTimeoutException() {
            super("gcloud timed out after " + TIMEOUT_SECONDS + " seconds");
        }
    }

    /**
     * Executes {@code gcloud <args>} and returns the combined result.
     *
     * @param args arguments to pass after "gcloud" (e.g. {@code List.of("projects", "list", "--format=json")})
     * @throws GcloudNotFoundException if the gcloud binary is not on PATH
     * @throws GcloudTimeoutException  if the process does not finish within the timeout
     * @throws IOException             for other I/O failures (e.g. interrupted while reading streams)
     */
    public CliResult execute(List<String> args)
            throws GcloudNotFoundException, GcloudTimeoutException, IOException {

        var command = new ArrayList<String>(args.size() + 1);
        command.add(GCLOUD);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GcloudNotFoundException(e);
        }

        // Read stdout and stderr concurrently on virtual threads to prevent blocking.
        var stdoutRef = new AtomicReference<String>("");
        var stderrRef = new AtomicReference<String>("");

        var stdoutThread = Thread.ofVirtual().start(() -> {
            try {
                stdoutRef.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        var stderrThread = Thread.ofVirtual().start(() -> {
            try {
                stderrRef.set(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for gcloud", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new GcloudTimeoutException();
        }

        try {
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new CliResult(stdoutRef.get(), stderrRef.get(), process.exitValue());
    }
}
