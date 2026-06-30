package dev.nuclr.plugin.core.panel.gcp;

/**
 * A single Cloud Storage bucket, with the fields shown as panel columns.
 * Values are already display-formatted by {@link GcsBucketParser} (dashes for
 * blanks, human-readable timestamps, friendly public-access labels).
 */
public record GcsBucket(
        String name,
        String created,
        String locationType,
        String location,
        String defaultStorageClass,
        String lastModified,
        String publicAccess) {
}
