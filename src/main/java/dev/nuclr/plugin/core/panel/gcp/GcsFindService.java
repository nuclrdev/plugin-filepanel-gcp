package dev.nuclr.plugin.core.panel.gcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a {@link GcsFindRequest} by streaming {@code gcloud storage ls --json <wildcard>} and
 * emitting the objects whose <b>file name</b> matches the request's glob. The gcloud process is
 * consumed incrementally (bounded memory, however large the bucket), matches are marshalled to the
 * EDT, and the search is cancellable — {@link SearchHandle#cancel()} destroys the process.
 *
 * <p>Search is by name only; object content is never read.
 */
@Slf4j
final class GcsFindService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Receives search results; every callback is delivered on the EDT. */
    interface Listener {
        void onMatch(NuclrResourceMatch match);

        void onProgress(long scanned, long matched);

        void onComplete(long scanned, long matched, boolean cancelled);
    }

    /** Small carrier so the listener needn't know how a hit is built. */
    record NuclrResourceMatch(NuclrResource resource) {}

    /** Handle to a running search: cooperative cancellation that also kills the gcloud process. */
    static final class SearchHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Process> process = new AtomicReference<>();

        void cancel() {
            cancelled.set(true);
            Process p = process.get();
            if (p != null) {
                p.destroyForcibly();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        private void attach(Process p) {
            process.set(p);
            if (cancelled.get()) {
                p.destroyForcibly(); // cancelled before the process started
            }
        }
    }

    /** Begin the search asynchronously; returns immediately with a handle for cancellation. */
    SearchHandle search(GcsFindRequest request, Listener listener) {
        SearchHandle handle = new SearchHandle();
        Thread.ofVirtual().name("gcs-find").start(() -> run(request, listener, handle));
        return handle;
    }

    private void run(GcsFindRequest request, Listener listener, SearchHandle handle) {
        AtomicLong scanned = new AtomicLong();
        AtomicLong matched = new AtomicLong();
        Pattern glob = compileGlob(request.namePattern(), request.caseSensitive());

        Process process = null;
        try {
            process = new ProcessBuilder(List.of(
                    GcloudCli.executable(), "storage", "ls", "--json", request.listUrl())).start();
            handle.attach(process);

            // Drain stderr so a full error pipe never blocks the process.
            Process finalProcess = process;
            Thread.ofVirtual().start(() -> drain(finalProcess));

            try (JsonParser parser = FACTORY.createParser(process.getInputStream())) {
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    stream(parser, request, glob, scanned, matched, listener, handle);
                }
            }
        } catch (IOException e) {
            log.warn("GCS find failed for {}: {}", request.listUrl(), e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            long s = scanned.get();
            long m = matched.get();
            boolean cancelled = handle.isCancelled();
            SwingUtilities.invokeLater(() -> listener.onComplete(s, m, cancelled));
        }
    }

    private void stream(JsonParser parser, GcsFindRequest request, Pattern glob, AtomicLong scanned,
            AtomicLong matched, Listener listener, SearchHandle handle) throws IOException {

        while (!handle.isCancelled()) {
            JsonToken token = parser.nextToken();
            if (token == null || token == JsonToken.END_ARRAY) {
                break;
            }
            if (token != JsonToken.START_OBJECT) {
                continue;
            }
            JsonNode node = MAPPER.readTree(parser);
            long total = scanned.incrementAndGet();

            NuclrResourceMatch match = toMatch(node, request, glob);
            if (match != null) {
                long m = matched.incrementAndGet();
                SwingUtilities.invokeLater(() -> listener.onMatch(match));
                if (m % 8 == 0) {
                    SwingUtilities.invokeLater(() -> listener.onProgress(scanned.get(), matched.get()));
                }
            }
            if (total % 256 == 0) {
                SwingUtilities.invokeLater(() -> listener.onProgress(scanned.get(), matched.get()));
            }
        }
    }

    /** Turn one listing element into a matching hit, or {@code null} if it does not match / is unusable. */
    private NuclrResourceMatch toMatch(JsonNode node, GcsFindRequest request, Pattern glob) {
        String type = node.path("type").asText("");
        if ("prefix".equals(type)) {
            String key = keyFromUrl(node.path("url").asText(""));
            String name = lastSegment(key);
            if (name.isEmpty() || !nameMatches(glob, name)) {
                return null;
            }
            return new NuclrResourceMatch(
                    GcpResource.objectDir(request.projectId(), request.bucket(), ensureSlash(key), name + "/"));
        }

        JsonNode meta = node.path("metadata");
        String key = meta.path("name").asText("");
        String name = lastSegment(key);
        if (name.isEmpty() || !nameMatches(glob, name)) {
            return null;
        }
        GcsObject object = new GcsObject(
                name,
                false,
                formatSize(meta.path("size").asText("")),
                blankToDash(meta.path("storageClass").asText("")),
                formatTimestamp(meta.path("updated").asText("")));
        return new NuclrResourceMatch(GcpResource.object(request.projectId(), request.bucket(), key, object));
    }

    private static boolean nameMatches(Pattern glob, String name) {
        return glob == null || glob.matcher(name).matches();
    }

    private static void drain(Process process) {
        try {
            process.getErrorStream().readAllBytes();
        } catch (IOException ignored) {
            // best-effort; the process is being torn down
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers (mirroring GcsObjectParser's formatting)
    // ------------------------------------------------------------------

    /** {@code gs://bucket/a/b/c.txt} → {@code a/b/c.txt} (without any {@code #generation} suffix). */
    private static String keyFromUrl(String url) {
        String s = url.startsWith("gs://") ? url.substring(5) : url;
        int slash = s.indexOf('/');
        String key = slash < 0 ? "" : s.substring(slash + 1);
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /** Last path segment (the file/folder name), ignoring a trailing slash. */
    private static String lastSegment(String key) {
        String trimmed = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private static String ensureSlash(String key) {
        return key.endsWith("/") ? key : key + "/";
    }

    private static Pattern compileGlob(String glob, boolean caseSensitive) {
        if (glob == null || glob.isBlank() || glob.equals("*")) {
            return null; // matches everything
        }
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile(globToRegex(glob), flags);
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String formatSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        long bytes;
        try {
            bytes = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        try {
            return OffsetDateTime.parse(raw).format(DISPLAY_TS);
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
