package dev.nuclr.plugin.core.panel.gcp.firestore;

/**
 * A Firestore database of a project, e.g. {@code (default)}. A database runs in one of two modes, fixed
 * at creation: Native mode is browsed through the Firestore API (collections and documents), Datastore
 * mode through the Datastore API (namespaces, kinds and entities).
 *
 * @param id       the database id, e.g. {@code (default)} or {@code orders}
 * @param datastore {@code true} for a Datastore-mode database, {@code false} for Native mode
 * @param location the location id, e.g. {@code nam5} or {@code europe-west1}, or {@code "-"}
 */
public record FirestoreDatabase(String id, boolean datastore, String location) {

	/** The id the Firestore API gives the default database. */
	public static final String DEFAULT_ID = "(default)";
}
