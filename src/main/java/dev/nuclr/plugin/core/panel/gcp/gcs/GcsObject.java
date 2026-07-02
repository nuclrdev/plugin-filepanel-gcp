package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * One immediate child of a Cloud Storage path as returned by
 * {@code gcloud storage ls --json}: either an object (leaf) or a sub-prefix (folder).
 * Values are already display-formatted by {@link GcsObjectParser}.
 */
public record GcsObject(
        String name,
        boolean folder,
        String size,
        String storageClass,
        String updated) {
}
