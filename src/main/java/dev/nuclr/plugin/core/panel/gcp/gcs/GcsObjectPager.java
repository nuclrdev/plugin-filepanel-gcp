package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A lazily-consumed listing of one Cloud Storage "directory".
 *
 * <p>Runs {@code gcloud storage ls --json gs://bucket/<prefix>} (delimiter-based, so only
 * immediate children — objects and sub-prefixes — are returned) and keeps the process and a
 * streaming JSON parser <b>open</b> between calls. {@link #nextPage} consumes the next batch
 * of array elements on demand; gcloud transparently fetches successive 1000-object API pages
 * as the stream is read, so memory stays bounded no matter how many objects the prefix holds.
 *
 * <p>The pager owns an OS process and must be {@link #close() closed} when the listing is
 * abandoned (navigating elsewhere, cancel, unload). Not thread-safe: the owner must serialize
 * {@link #nextPage} and {@link #close}.
 */
public final class GcsObjectPager implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    /** Carries a classified {@link GcpError} when a listing cannot be started. */
    public static final class GcsListException extends Exception {
        private final transient GcpError error;
        GcsListException(GcpError error) {
            this.error = error;
        }
        public GcpError error() {
            return error;
        }
    }

    private final String prefix;
    private final Process process;
    private final JsonParser parser;
    private boolean exhausted;

    private GcsObjectPager(String prefix, Process process, JsonParser parser, boolean exhausted) {
        this.prefix = prefix;
        this.process = process;
        this.parser = parser;
        this.exhausted = exhausted;
    }

    /**
     * Starts a listing of {@code gs://bucket/<prefix>}. The {@code prefix} is an object-key
     * prefix ending in {@code /}, or {@code ""} for the bucket root.
     *
     * @throws GcsListException if gcloud is missing, the account is not authorized, or the
     *                          command fails before producing a listing
     */
    public static GcsObjectPager open(String bucket, String prefix) throws GcsListException {
        String url = "gs://" + bucket + "/" + prefix;
        Process process;
        try {
            process = new ProcessBuilder(
                    List.of(GcloudCli.executable(), "storage", "ls", "--json", url)).start();
        } catch (IOException e) {
            throw new GcsListException(new GcpError.GcloudNotFound());
        }

        // Drain stderr concurrently so the process never blocks on a full error pipe.
        var stderrRef = new AtomicReference<String>("");
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try {
                stderrRef.set(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });

        JsonToken first;
        JsonParser parser;
        try {
            parser = FACTORY.createParser(process.getInputStream());
            first = parser.nextToken();
        } catch (IOException e) {
            first = null;
            parser = null;
        }

        if (first == JsonToken.START_ARRAY) {
            return new GcsObjectPager(prefix, process, parser, false);
        }

        // No JSON array: an empty directory, or a failure.
        closeQuietly(parser);
        int exit = waitFor(process);
        joinQuietly(stderrDrain);
        process.destroy();
        String stderr = stderrRef.get();

        // gcloud exits non-zero with this message when a path has no children; for a file
        // browser that is simply an empty folder, not an error worth a dialog.
        boolean emptyListing = exit == 0
                || stderr.toLowerCase(Locale.ROOT).contains("matched no objects");
        if (emptyListing) {
            return new GcsObjectPager(prefix, process, null, true);
        }
        throw new GcsListException(GcloudErrors.classify(stderr));
    }

    /**
     * Reads and returns up to {@code max} more entries, advancing the stream. Returns an empty
     * list once the listing is exhausted (or immediately if cancelled). Names are relative to
     * the listed prefix.
     */
    public List<GcsObject> nextPage(int max, AtomicBoolean cancelled) {
        var page = new ArrayList<GcsObject>(Math.min(max, 1024));
        if (exhausted || parser == null) {
            return page;
        }
        try {
            while (page.size() < max) {
                if (cancelled != null && cancelled.get()) {
                    break;
                }
                JsonToken token = parser.nextToken();
                if (token == null || token == JsonToken.END_ARRAY) {
                    exhausted = true;
                    break;
                }
                if (token == JsonToken.START_OBJECT) {
                    JsonNode node = MAPPER.readTree(parser);
                    GcsObject entry = GcsObjectParser.fromNode(node, prefix);
                    if (entry != null) {
                        page.add(entry);
                    }
                }
            }
        } catch (IOException e) {
            exhausted = true; // stream broke; stop cleanly
        }
        return page;
    }

    /** Whether another {@link #nextPage} call might yield more entries. */
    public boolean hasMore() {
        return !exhausted;
    }

    @Override
    public void close() {
        exhausted = true;
        closeQuietly(parser);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void closeQuietly(JsonParser parser) {
        if (parser != null) {
            try {
                parser.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int waitFor(Process process) {
        try {
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return -1;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
