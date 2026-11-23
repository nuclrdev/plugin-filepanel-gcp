package dev.nuclr.plugin.core.panel.gcp.firestore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a project's Firestore databases over REST: collections and documents of Native-mode databases
 * through the Firestore API, namespaces, kinds and entities of Datastore-mode databases through the
 * Datastore API. Read-only. No Swing.
 *
 * <p>Firestore bills each document returned, so documents and entities are fetched a page at a time;
 * only the cheap name listings (collection ids, kinds, namespaces) are read to the end.
 */
public class FirestoreRepository {

	private static final String FIRESTORE = "https://firestore.googleapis.com/v1/";
	private static final String DATASTORE = "https://datastore.googleapis.com/v1/";

	/** Upper bound on pages read for a name listing, so a runaway listing cannot loop forever. */
	private static final int MAX_NAME_PAGES = 50;
	private static final int NAME_PAGE_SIZE = 500;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final FirestoreHttp http;

	public FirestoreRepository() {
		this(new FirestoreHttp());
	}

	public FirestoreRepository(FirestoreHttp http) {
		this.http = http;
	}

	// -------------------------------------------------------------------------
	// Databases
	// -------------------------------------------------------------------------

	public List<FirestoreDatabase> listDatabases(String projectId) throws FirestoreException {
		JsonNode root = http.get(URI.create(FIRESTORE + "projects/" + segment(projectId) + "/databases"));
		var databases = new ArrayList<FirestoreDatabase>();
		for (JsonNode node : root.path("databases")) {
			String id = lastSegment(node.path("name").asText(""));
			if (id.isBlank()) {
				continue;
			}
			databases.add(new FirestoreDatabase(
					id,
					"DATASTORE_MODE".equals(node.path("type").asText("")),
					dash(node.path("locationId").asText(""))));
		}
		// The default database first, the rest by name.
		databases.sort((a, b) -> FirestoreDatabase.DEFAULT_ID.equals(a.id()) ? -1
				: FirestoreDatabase.DEFAULT_ID.equals(b.id()) ? 1 : a.id().compareTo(b.id()));
		return List.copyOf(databases);
	}

	// -------------------------------------------------------------------------
	// Native mode
	// -------------------------------------------------------------------------

	/**
	 * The collection ids directly under {@code documentPath} ({@code ""} for the database's top-level
	 * collections), in name order.
	 */
	public List<String> listCollectionIds(String projectId, String database, String documentPath,
			BooleanSupplier cancelled) throws FirestoreException {
		URI uri = URI.create(documentsBase(projectId, database)
				+ (documentPath == null || documentPath.isEmpty() ? "" : "/" + path(documentPath))
				+ ":listCollectionIds");
		var ids = new ArrayList<String>();
		String token = null;
		for (int page = 0; page < MAX_NAME_PAGES && !isCancelled(cancelled); page++) {
			ObjectNode body = NODES.objectNode().put("pageSize", NAME_PAGE_SIZE);
			if (token != null) {
				body.put("pageToken", token);
			}
			JsonNode root = http.post(uri, body);
			for (JsonNode id : root.path("collectionIds")) {
				ids.add(id.asText(""));
			}
			token = root.path("nextPageToken").asText("");
			if (token.isBlank()) {
				break;
			}
		}
		return List.copyOf(ids);
	}

	/**
	 * One page of the documents in {@code collectionPath} (e.g. {@code users} or {@code users/alice/orders}),
	 * in id order. Documents that do not exist but have subcollections are included, marked missing, so
	 * their subcollections stay reachable.
	 */
	public FirestorePage<FirestoreDocument> listDocuments(String projectId, String database, String collectionPath,
			int pageSize, String pageToken) throws FirestoreException {
		var uri = new StringBuilder(documentsBase(projectId, database)).append('/').append(path(collectionPath))
				.append("?pageSize=").append(pageSize)
				.append("&showMissing=true");
		if (pageToken != null && !pageToken.isBlank()) {
			uri.append("&pageToken=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
		}
		JsonNode root = http.get(URI.create(uri.toString()));
		var documents = new ArrayList<FirestoreDocument>();
		String prefix = "projects/" + projectId + "/databases/" + database + "/documents/";
		for (JsonNode node : root.path("documents")) {
			documents.add(document(node, prefix));
		}
		String next = root.path("nextPageToken").asText("");
		return new FirestorePage<>(List.copyOf(documents), next.isBlank() ? null : next);
	}

	/** One document, or {@code null} when it does not exist (it may still have subcollections). */
	public FirestoreDocument getDocument(String projectId, String database, String documentPath)
			throws FirestoreException {
		try {
			JsonNode node = http.get(URI.create(documentsBase(projectId, database) + "/" + path(documentPath)));
			return document(node, "projects/" + projectId + "/databases/" + database + "/documents/");
		} catch (FirestoreException e) {
			if (e.status() == 404) {
				return null;
			}
			throw e;
		}
	}

	private static FirestoreDocument document(JsonNode node, String prefix) {
		String name = node.path("name").asText("");
		String path = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
		boolean missing = !node.has("createTime");
		ObjectNode fields = FirestoreValues.plainObject(node.path("fields"));
		return new FirestoreDocument(
				path,
				lastSegment(path),
				FirestoreValues.pretty(fields),
				fields.size(),
				time(node.path("createTime").asText("")),
				time(node.path("updateTime").asText("")),
				missing);
	}

	// -------------------------------------------------------------------------
	// Datastore mode
	// -------------------------------------------------------------------------

	/** The named namespaces of a Datastore-mode database (the default namespace is left out). */
	public List<String> listNamespaces(String projectId, String database, BooleanSupplier cancelled)
			throws FirestoreException {
		var names = new ArrayList<String>();
		for (JsonNode key : metadataKeys(projectId, database, "", "__namespace__", cancelled)) {
			String name = key.path("path").path(0).path("name").asText("");
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return List.copyOf(names);
	}

	/** The kinds in one namespace ({@code ""} for the default), without the built-in {@code __...} kinds. */
	public List<String> listKinds(String projectId, String database, String namespace, BooleanSupplier cancelled)
			throws FirestoreException {
		var kinds = new ArrayList<String>();
		for (JsonNode key : metadataKeys(projectId, database, namespace, "__kind__", cancelled)) {
			String name = key.path("path").path(0).path("name").asText("");
			if (!name.isEmpty() && !name.startsWith("__")) {
				kinds.add(name);
			}
		}
		return List.copyOf(kinds);
	}

	/** One page of the entities of a kind, in key order; {@code cursor} is the previous page's end. */
	public FirestorePage<FirestoreDocument> listEntities(String projectId, String database, String namespace,
			String kind, int limit, String cursor) throws FirestoreException {
		ObjectNode query = NODES.objectNode();
		query.putArray("kind").addObject().put("name", kind);
		query.put("limit", limit);
		if (cursor != null && !cursor.isBlank()) {
			query.put("startCursor", cursor);
		}
		JsonNode batch = runQuery(projectId, database, namespace, query).path("batch");
		var entities = new ArrayList<FirestoreDocument>();
		for (JsonNode result : batch.path("entityResults")) {
			JsonNode entity = result.path("entity");
			JsonNode key = entity.path("key");
			JsonNode path = key.path("path");
			ObjectNode properties = FirestoreValues.plainObject(entity.path("properties"));
			entities.add(new FirestoreDocument(
					FirestoreValues.keyPath(key),
					FirestoreValues.keyId(path.path(path.size() - 1)),
					FirestoreValues.pretty(properties),
					properties.size(),
					"-",
					time(result.path("updateTime").asText("")),
					false));
		}
		String more = batch.path("moreResults").asText("");
		String end = batch.path("endCursor").asText("");
		boolean hasMore = !"NO_MORE_RESULTS".equals(more) && !entities.isEmpty() && !end.isBlank();
		return new FirestorePage<>(List.copyOf(entities), hasMore ? end : null);
	}

	/** Keys-only query over a metadata kind ({@code __kind__}, {@code __namespace__}), read to the end. */
	private List<JsonNode> metadataKeys(String projectId, String database, String namespace, String metadataKind,
			BooleanSupplier cancelled) throws FirestoreException {
		var keys = new ArrayList<JsonNode>();
		String cursor = null;
		for (int page = 0; page < MAX_NAME_PAGES && !isCancelled(cancelled); page++) {
			ObjectNode query = NODES.objectNode();
			query.putArray("kind").addObject().put("name", metadataKind);
			query.putArray("projection").addObject().putObject("property").put("name", "__key__");
			query.put("limit", NAME_PAGE_SIZE);
			if (cursor != null) {
				query.put("startCursor", cursor);
			}
			JsonNode batch = runQuery(projectId, database, namespace, query).path("batch");
			int count = 0;
			for (JsonNode result : batch.path("entityResults")) {
				keys.add(result.path("entity").path("key"));
				count++;
			}
			cursor = batch.path("endCursor").asText("");
			if (count == 0 || cursor.isBlank() || "NO_MORE_RESULTS".equals(batch.path("moreResults").asText(""))) {
				break;
			}
		}
		return keys;
	}

	private JsonNode runQuery(String projectId, String database, String namespace, ObjectNode query)
			throws FirestoreException {
		ObjectNode body = NODES.objectNode();
		ObjectNode partition = body.putObject("partitionId").put("projectId", projectId);
		// The Datastore API names the default database "", not "(default)".
		if (database != null && !database.isEmpty() && !FirestoreDatabase.DEFAULT_ID.equals(database)) {
			body.put("databaseId", database);
			partition.put("databaseId", database);
		}
		if (namespace != null && !namespace.isEmpty()) {
			partition.put("namespaceId", namespace);
		}
		body.set("query", query);
		return http.post(URI.create(DATASTORE + "projects/" + segment(projectId) + ":runQuery"), body);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static String documentsBase(String projectId, String database) {
		return FIRESTORE + "projects/" + segment(projectId) + "/databases/" + segment(database) + "/documents";
	}

	/** A slash-separated path with each segment encoded. */
	static String path(String path) {
		String[] segments = path.split("/", -1);
		var out = new StringBuilder();
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				out.append('/');
			}
			out.append(segment(segments[i]));
		}
		return out.toString();
	}

	/** One URL path segment; parentheses stay as they are so {@code (default)} reads as Google writes it. */
	static String segment(String segment) {
		return URLEncoder.encode(segment, StandardCharsets.UTF_8)
				.replace("+", "%20").replace("%28", "(").replace("%29", ")");
	}

	/** {@code users/alice/orders} → {@code orders}. */
	public static String lastSegment(String path) {
		if (path == null) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	/** {@code users/alice/orders} → {@code users/alice}; {@code users} → {@code ""}. */
	public static String parentPath(String path) {
		if (path == null) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	/** {@code 2024-05-01T10:20:30.123456Z} → {@code 2024-05-01 10:20:30}. */
	static String time(String rfc3339) {
		if (rfc3339 == null || rfc3339.length() < 19) {
			return "-";
		}
		return rfc3339.substring(0, 10) + " " + rfc3339.substring(11, 19);
	}

	private static String dash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static boolean isCancelled(BooleanSupplier cancelled) {
		return cancelled != null && cancelled.getAsBoolean();
	}
}
