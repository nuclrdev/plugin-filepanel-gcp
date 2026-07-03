package dev.nuclr.plugin.core.panel.gcp.gke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes {@code kubectl} commands against whatever context {@code gcloud container clusters
 * get-credentials} last set, returning their raw output. No parsing, no Swing — pure I/O layer.
 *
 * <p>Mirrors {@link dev.nuclr.plugin.core.panel.gcp.GcloudCli}, but tries a few executable names in
 * turn ({@code kubectl.exe}, {@code kubectl.cmd}, {@code kubectl} on Windows) because
 * {@link ProcessBuilder} does not honor {@code PATHEXT} and kubectl ships under different names
 * depending on how it was installed.
 */
public class KubectlCli {

    private static final int TIMEOUT_SECONDS = 20;

    private static final List<String> CANDIDATES =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                    ? List.of("kubectl.exe", "kubectl.cmd", "kubectl")
                    : List.of("kubectl");

    /** Raw result of a kubectl invocation. */
    public record CliResult(String stdout, String stderr, int exitCode) {}

    /** Thrown when no kubectl binary can be found on PATH. */
    public static final class KubectlNotFoundException extends Exception {
        KubectlNotFoundException(Throwable cause) {
            super("kubectl CLI not found on PATH", cause);
        }
    }

    /** Thrown when kubectl does not respond within {@value #TIMEOUT_SECONDS} seconds. */
    public static final class KubectlTimeoutException extends Exception {
        KubectlTimeoutException() {
            super("kubectl timed out after " + TIMEOUT_SECONDS + " seconds");
        }
    }

    /** Executes {@code kubectl <args>}, trying each candidate executable name until one starts. */
    public CliResult execute(List<String> args)
            throws KubectlNotFoundException, KubectlTimeoutException, IOException {
        KubectlNotFoundException notFound = null;
        for (String exe : CANDIDATES) {
            try {
                return run(exe, args);
            } catch (KubectlNotFoundException e) {
                notFound = e; // try the next candidate name
            }
        }
        throw notFound != null ? notFound : new KubectlNotFoundException(null);
    }

    private CliResult run(String exe, List<String> args)
            throws KubectlNotFoundException, KubectlTimeoutException, IOException {

        var command = new ArrayList<String>(args.size() + 1);
        command.add(exe);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new KubectlNotFoundException(e);
        }

        var stdoutRef = new AtomicReference<>("");
        var stderrRef = new AtomicReference<>("");

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
            throw new IOException("Interrupted while waiting for kubectl", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new KubectlTimeoutException();
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
