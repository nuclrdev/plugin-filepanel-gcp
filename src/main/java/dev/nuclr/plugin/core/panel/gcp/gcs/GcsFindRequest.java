package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * An immutable "Find files" request for the GCP panel: a filename wildcard pattern matched against
 * the objects under {@code gs://bucket/prefix}. Search is by <b>name only</b> (never content).
 *
 * @param projectId     owning project (carried onto result resources)
 * @param bucket        bucket to search
 * @param prefix        starting object-key prefix ({@code ""} for the bucket root), ends in {@code /}
 * @param namePattern   filename glob ({@code *} and {@code ?}); {@code *} or blank matches everything
 * @param recursive     whether to descend into sub-folders ({@code **}) or search only this level
 * @param caseSensitive whether the name match is case-sensitive
 */
public record GcsFindRequest(
        String projectId,
        String bucket,
        String prefix,
        String namePattern,
        boolean recursive,
        boolean caseSensitive) {

    /** The gs:// wildcard URL gcloud lists to enumerate candidate objects. */
    String listUrl() {
        return "gs://" + bucket + "/" + prefix + (recursive ? "**" : "*");
    }

    public String title() {
        String p = namePattern == null || namePattern.isBlank() ? "*" : namePattern;
        return "Find: " + p + " in gs://" + bucket + "/" + prefix;
    }
}
