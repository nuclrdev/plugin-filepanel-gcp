package dev.nuclr.plugin.core.panel.gcp.firestore;

/**
 * A Firestore document (Native mode), or a Datastore entity, ready for display.
 *
 * @param path       the document path relative to the database, e.g. {@code users/alice}; for a Datastore
 *                   entity, its key path, e.g. {@code Task/sample}
 * @param id         the last path segment, e.g. {@code alice}
 * @param json       the fields as plain, pretty-printed JSON
 * @param fieldCount number of top-level fields
 * @param created    creation time for display, or {@code "-"}
 * @param updated    last update time for display, or {@code "-"}
 * @param missing    {@code true} for a document that does not exist but has subcollections
 */
public record FirestoreDocument(
		String path, String id, String json, int fieldCount, String created, String updated, boolean missing) {
}
