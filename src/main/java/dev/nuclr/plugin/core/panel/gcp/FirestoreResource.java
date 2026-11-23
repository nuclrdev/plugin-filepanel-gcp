package dev.nuclr.plugin.core.panel.gcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreDatabase;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreDocument;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreRepository;
import dev.nuclr.platform.plugin.NuclrResource;

/**
 * The Firestore entries of the GCP panel, all {@link GcpResource}s:
 * <ul>
 *   <li>a <b>database</b> under the Firestore service, then, for a Native-mode database,</li>
 *   <li><b>collections</b> (folders), whose documents are listed as <b>document</b> leaves named
 *       {@code <id>.json} that view as their fields; Enter on one opens the <b>document</b> folder, which
 *       holds the same leaf and the document's subcollections;</li>
 *   <li>for a Datastore-mode database, <b>namespaces</b> and <b>kinds</b> (folders) and <b>entity</b>
 *       leaves;</li>
 *   <li>a <b>load more</b> entry closing a listing that has further pages.</li>
 * </ul>
 * Paths are relative to the database: a collection is {@code users} or {@code users/alice/orders}, a
 * document {@code users/alice}.
 */
final class FirestoreResource {

	static final String KIND_DATABASE = "fs-database";
	static final String KIND_COLLECTION = "fs-collection";
	static final String KIND_DOCUMENT = "fs-document";
	static final String KIND_DOCUMENT_JSON = "fs-document-json";
	static final String KIND_NAMESPACE = "ds-namespace";
	static final String KIND_ENTITY_KIND = "ds-kind";
	static final String KIND_ENTITY = "ds-entity";
	static final String KIND_LOAD_MORE = "fs-load-more";

	private static final String DATABASE = "nuclr.gcp.fs.database";
	private static final String DATASTORE = "nuclr.gcp.fs.datastore";
	private static final String PATH = "nuclr.gcp.fs.path";
	private static final String NAMESPACE = "nuclr.gcp.fs.namespace";
	private static final String ENTITY_KIND = "nuclr.gcp.fs.kind";
	/** On a document leaf in a collection listing: Enter opens the document's folder. */
	private static final String OPENS_DOCUMENT = "nuclr.gcp.fs.opensDocument";
	/** On a load-more entry: the listing it continues. */
	private static final String LISTING = "nuclr.gcp.fs.listing";

	private FirestoreResource() {}

	private static String base(String projectId, String database) {
		return GcpResource.ROOT_UUID + "firestore/" + projectId + "/" + database + "/";
	}

	private static GcpResource node(String uuid, String kind, String projectId, String database, boolean datastore,
			String name, boolean folder) {
		GcpResource r = GcpResource.virtual(uuid, kind, projectId, name, folder);
		r.getMetadata().put(DATABASE, database);
		r.getMetadata().put(DATASTORE, datastore);
		return r;
	}

	// -------------------------------------------------------------------------
	// Factories
	// -------------------------------------------------------------------------

	/** A database row in the Firestore service listing. */
	static GcpResource database(String projectId, FirestoreDatabase database) {
		GcpResource r = databaseRef(projectId, database.id(), database.datastore());
		r.getMetadata().put("Mode", database.datastore() ? "Datastore" : "Native");
		r.getMetadata().put("Location", database.location());
		return r;
	}

	/** A database identified by id and mode: the current location, or a ".." target. */
	static GcpResource databaseRef(String projectId, String database, boolean datastore) {
		return node(base(projectId, database), KIND_DATABASE, projectId, database, datastore, database, true);
	}

	static GcpResource collection(String projectId, String database, String path) {
		GcpResource r = node(base(projectId, database) + "c/" + path, KIND_COLLECTION, projectId, database, false,
				FirestoreRepository.lastSegment(path), true);
		r.getMetadata().put(PATH, path);
		r.getMetadata().put("Type", "Collection");
		return r;
	}

	/** A document's folder, holding its fields leaf and its subcollections. */
	static GcpResource document(String projectId, String database, String path) {
		GcpResource r = node(base(projectId, database) + "d/" + path, KIND_DOCUMENT, projectId, database, false,
				FirestoreRepository.lastSegment(path), true);
		r.getMetadata().put(PATH, path);
		return r;
	}

	/**
	 * A document leaf, viewed as its fields. {@code opensDocument} marks the copy in a collection listing,
	 * where Enter steps into the document; the copy inside the document's folder opens the Cloud Console.
	 */
	static GcpResource documentJson(String projectId, String database, FirestoreDocument document,
			boolean opensDocument) {
		GcpResource r = node(base(projectId, database) + (opensDocument ? "j/" : "f/") + document.path(),
				KIND_DOCUMENT_JSON, projectId, database, false, document.id() + ".json", false);
		r.getMetadata().put(PATH, document.path());
		r.getMetadata().put(OPENS_DOCUMENT, opensDocument);
		r.getMetadata().put("Type", "Document");
		r.getMetadata().put("Fields", document.missing() ? "-" : String.valueOf(document.fieldCount()));
		r.getMetadata().put("Created", document.created());
		r.getMetadata().put("Updated", document.updated());
		r.getMetadata().put(GcpResource.CONSOLE_URL, documentConsoleUrl(projectId, database, document.path()));
		return r.content(document.json());
	}

	static GcpResource namespace(String projectId, String database, String namespace) {
		GcpResource r = node(base(projectId, database) + "ns/" + namespace, KIND_NAMESPACE, projectId, database, true,
				namespace, true);
		r.getMetadata().put(NAMESPACE, namespace);
		r.getMetadata().put("Type", "Namespace");
		return r;
	}

	static GcpResource entityKind(String projectId, String database, String namespace, String kind) {
		GcpResource r = node(base(projectId, database) + "k/" + namespace + "/" + kind, KIND_ENTITY_KIND, projectId,
				database, true, kind, true);
		r.getMetadata().put(NAMESPACE, namespace);
		r.getMetadata().put(ENTITY_KIND, kind);
		r.getMetadata().put("Type", "Kind");
		return r;
	}

	static GcpResource entity(String projectId, String database, String namespace, FirestoreDocument entity) {
		GcpResource r = node(base(projectId, database) + "e/" + namespace + "/" + entity.path(), KIND_ENTITY,
				projectId, database, true, entity.id() + ".json", false);
		r.getMetadata().put(NAMESPACE, namespace);
		r.getMetadata().put(PATH, entity.path());
		r.getMetadata().put("Properties", String.valueOf(entity.fieldCount()));
		r.getMetadata().put("Updated", entity.updated());
		return r.content(entity.json());
	}

	/** The entry that fetches the next page of {@code listing} (a collection or a kind). */
	static GcpResource loadMore(NuclrResource listing) {
		GcpResource r = node(listing.getUuid() + " load-more", KIND_LOAD_MORE, GcpResource.projectId(listing),
				database(listing), isDatastore(listing), Messages.tr("load.more"), true);
		r.getMetadata().put(LISTING, listing);
		return r;
	}

	/** The ".." entry of a listing: where going up from {@code resource} leads. */
	static GcpResource parentOf(NuclrResource resource) {
		String projectId = GcpResource.projectId(resource);
		String database = database(resource);
		return switch (kind(resource)) {
			case KIND_DATABASE -> GcpResource.firestoreService(projectId).asParent();
			case KIND_COLLECTION -> {
				String parent = FirestoreRepository.parentPath(path(resource));
				yield (parent.isEmpty() ? databaseRef(projectId, database, false) : document(projectId, database, parent))
						.asParent();
			}
			case KIND_DOCUMENT -> collection(projectId, database, FirestoreRepository.parentPath(path(resource))).asParent();
			case KIND_NAMESPACE -> databaseRef(projectId, database, true).asParent();
			case KIND_ENTITY_KIND -> (namespace(resource).isEmpty()
					? databaseRef(projectId, database, true)
					: namespace(projectId, database, namespace(resource))).asParent();
			default -> GcpResource.firestoreService(projectId).asParent();
		};
	}

	/** A fresh copy of a listing node, so the location bar never shows the ".." it was entered through. */
	static GcpResource clean(NuclrResource resource) {
		String projectId = GcpResource.projectId(resource);
		String database = database(resource);
		return switch (kind(resource)) {
			case KIND_COLLECTION -> collection(projectId, database, path(resource));
			case KIND_DOCUMENT -> document(projectId, database, path(resource));
			case KIND_NAMESPACE -> namespace(projectId, database, namespace(resource));
			case KIND_ENTITY_KIND -> entityKind(projectId, database, namespace(resource), entityKind(resource));
			default -> databaseRef(projectId, database, isDatastore(resource));
		};
	}

	// -------------------------------------------------------------------------
	// Accessors
	// -------------------------------------------------------------------------

	static boolean isFirestore(NuclrResource resource) {
		String kind = kind(resource);
		return kind != null && (kind.startsWith("fs-") || kind.startsWith("ds-"));
	}

	static String kind(NuclrResource resource) {
		Object kind = resource == null ? null : resource.getMetadata().get(GcpResource.KIND);
		return kind == null ? "" : kind.toString();
	}

	static boolean is(NuclrResource resource, String kind) {
		return kind.equals(kind(resource));
	}

	static String database(NuclrResource resource) {
		return string(resource, DATABASE);
	}

	static boolean isDatastore(NuclrResource resource) {
		return resource != null && Boolean.TRUE.equals(resource.getMetadata().get(DATASTORE));
	}

	static String path(NuclrResource resource) {
		return string(resource, PATH);
	}

	static String namespace(NuclrResource resource) {
		return string(resource, NAMESPACE);
	}

	static String entityKind(NuclrResource resource) {
		return string(resource, ENTITY_KIND);
	}

	static boolean opensDocument(NuclrResource resource) {
		return is(resource, KIND_DOCUMENT_JSON) && Boolean.TRUE.equals(resource.getMetadata().get(OPENS_DOCUMENT));
	}

	static NuclrResource listing(NuclrResource loadMore) {
		return loadMore != null && loadMore.getMetadata().get(LISTING) instanceof NuclrResource r ? r : null;
	}

	/** The Cloud Console page of a document leaf, or {@code null} for any other resource. */
	static String consoleUrl(NuclrResource resource) {
		return is(resource, KIND_DOCUMENT_JSON) ? string(resource, GcpResource.CONSOLE_URL) : null;
	}

	/** Location text below the project: {@code Firestore / (default) / users/alice}. */
	static String locationText(NuclrResource resource) {
		var out = new StringBuilder("Firestore / ").append(database(resource));
		String detail = switch (kind(resource)) {
			case KIND_COLLECTION, KIND_DOCUMENT -> path(resource);
			case KIND_NAMESPACE -> namespace(resource);
			case KIND_ENTITY_KIND -> namespace(resource).isEmpty()
					? entityKind(resource)
					: namespace(resource) + " / " + entityKind(resource);
			default -> "";
		};
		if (!detail.isEmpty()) {
			out.append(" / ").append(detail);
		}
		return out.toString();
	}

	/**
	 * {@code https://console.cloud.google.com/firestore/databases/-default-/data/panel/users/alice?project=p};
	 * the Console writes the default database as {@code -default-}.
	 */
	static String documentConsoleUrl(String projectId, String database, String path) {
		String db = FirestoreDatabase.DEFAULT_ID.equals(database) ? "-default-" : database;
		var url = new StringBuilder("https://console.cloud.google.com/firestore/databases/")
				.append(encode(db)).append("/data/panel");
		for (String segment : path.split("/")) {
			url.append('/').append(encode(segment));
		}
		return url.append("?project=").append(encode(projectId)).toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String string(NuclrResource resource, String key) {
		Object value = resource == null ? null : resource.getMetadata().get(key);
		return value == null ? "" : value.toString();
	}
}
