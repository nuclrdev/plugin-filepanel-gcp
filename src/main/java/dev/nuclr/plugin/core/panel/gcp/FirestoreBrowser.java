package dev.nuclr.plugin.core.panel.gcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreDatabase;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreDocument;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreException;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestorePage;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreRepository;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.EntrySink;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the Firestore listings of the GCP panel (see {@link FirestoreResource} for the tree). Everything
 * is fetched live: documents and entities a page at a time, since Firestore bills every one returned, with
 * a "Load more" entry while more remain. Errors are shown through {@link GcpErrorDialog} and leave the
 * listing with just its "..".
 */
@Slf4j
class FirestoreBrowser {

	static final List<String> DATABASE_COLUMNS = List.of("Name", "Mode", "Location");
	static final List<String> NODE_COLUMNS = List.of("Name", "Type");
	static final List<String> DOCUMENT_COLUMNS = List.of("Name", "Fields", "Updated", "Created");
	static final List<String> ENTITY_COLUMNS = List.of("Name", "Properties", "Updated");

	/** Documents or entities per page; each one returned is a billed read. */
	static final int PAGE_SIZE = 100;

	private final FirestoreRepository repository;

	// The paged listing on screen: its node, the rows shown so far, and the token of the next page.
	private String pagedUuid;
	private final List<NuclrResource> pagedRows = new ArrayList<>();
	private String nextToken;

	FirestoreBrowser() {
		this(new FirestoreRepository());
	}

	FirestoreBrowser(FirestoreRepository repository) {
		this.repository = repository;
	}

	/** The node to adopt as the panel's current location when {@code resource} is opened. */
	GcpResource location(NuclrResource resource) {
		if (FirestoreResource.is(resource, FirestoreResource.KIND_LOAD_MORE)) {
			return FirestoreResource.clean(FirestoreResource.listing(resource));
		}
		return FirestoreResource.clean(resource);
	}

	/** The Firestore service: the project's databases. */
	synchronized NuclrResourceData listDatabases(String projectId, AtomicBoolean cancelled, EntrySink sink) {
		reset();
		var data = start(DATABASE_COLUMNS, sink);
		add(data, sink, GcpResource.parentToProject(projectId));
		try {
			for (FirestoreDatabase database : repository.listDatabases(projectId)) {
				if (isCancelled(cancelled)) {
					break;
				}
				add(data, sink, FirestoreResource.database(projectId, database));
			}
		} catch (FirestoreException e) {
			fail("Firestore database list for " + projectId, e);
		}
		return data;
	}

	/** Open a Firestore node: a database, collection, document, namespace, kind, or "Load more". */
	synchronized NuclrResourceData open(NuclrResource resource, AtomicBoolean cancelled, EntrySink sink) {
		if (FirestoreResource.is(resource, FirestoreResource.KIND_LOAD_MORE)) {
			NuclrResource listing = FirestoreResource.listing(resource);
			if (listing != null && listing.getUuid().equals(pagedUuid) && nextToken != null) {
				return nextPage(FirestoreResource.clean(listing), cancelled, sink);
			}
			resource = listing; // the paging state is gone: start the listing over
			if (resource == null) {
				return null;
			}
		}
		reset();
		GcpResource node = FirestoreResource.clean(resource);
		return switch (FirestoreResource.kind(node)) {
			case FirestoreResource.KIND_DATABASE -> FirestoreResource.isDatastore(node)
					? listDatastoreRoot(node, cancelled, sink)
					: listCollections(node, "", cancelled, sink);
			case FirestoreResource.KIND_DOCUMENT -> listDocument(node, cancelled, sink);
			case FirestoreResource.KIND_NAMESPACE -> listKinds(node, FirestoreResource.namespace(node), cancelled, sink);
			case FirestoreResource.KIND_COLLECTION, FirestoreResource.KIND_ENTITY_KIND -> {
				pagedUuid = node.getUuid();
				yield nextPage(node, cancelled, sink);
			}
			default -> null;
		};
	}

	/** Drop the paging state, e.g. when the panel leaves Firestore. */
	synchronized void reset() {
		pagedUuid = null;
		pagedRows.clear();
		nextToken = null;
	}

	// -------------------------------------------------------------------------
	// Native mode
	// -------------------------------------------------------------------------

	/** A database root or a document's subcollections, as folders after the "..". */
	private NuclrResourceData listCollections(GcpResource node, String documentPath, AtomicBoolean cancelled,
			EntrySink sink) {
		var data = start(NODE_COLUMNS, sink);
		add(data, sink, FirestoreResource.parentOf(node));
		addCollections(data, sink, node, documentPath, cancelled);
		return data;
	}

	private void addCollections(NuclrResourceData data, EntrySink sink, GcpResource node, String documentPath,
			AtomicBoolean cancelled) {
		String projectId = GcpResource.projectId(node);
		String database = FirestoreResource.database(node);
		try {
			for (String id : repository.listCollectionIds(projectId, database, documentPath, supplier(cancelled))) {
				String path = documentPath.isEmpty() ? id : documentPath + "/" + id;
				add(data, sink, FirestoreResource.collection(projectId, database, path));
			}
		} catch (FirestoreException e) {
			fail("Firestore collection list for " + projectId + "/" + database + "/" + documentPath, e);
		}
	}

	/** A document's folder: "..", its fields leaf (when it exists), then its subcollections. */
	private NuclrResourceData listDocument(GcpResource node, AtomicBoolean cancelled, EntrySink sink) {
		String projectId = GcpResource.projectId(node);
		String database = FirestoreResource.database(node);
		String path = FirestoreResource.path(node);
		var data = start(NODE_COLUMNS, sink);
		add(data, sink, FirestoreResource.parentOf(node));
		try {
			FirestoreDocument document = repository.getDocument(projectId, database, path);
			if (document != null) {
				add(data, sink, FirestoreResource.documentJson(projectId, database, document, false));
			}
		} catch (FirestoreException e) {
			fail("Firestore document " + projectId + "/" + database + "/" + path, e);
			return data;
		}
		addCollections(data, sink, node, path, cancelled);
		return data;
	}

	// -------------------------------------------------------------------------
	// Datastore mode
	// -------------------------------------------------------------------------

	/** A Datastore-mode database: its named namespaces, then the kinds of the default namespace. */
	private NuclrResourceData listDatastoreRoot(GcpResource node, AtomicBoolean cancelled, EntrySink sink) {
		String projectId = GcpResource.projectId(node);
		String database = FirestoreResource.database(node);
		var data = start(NODE_COLUMNS, sink);
		add(data, sink, FirestoreResource.parentOf(node));
		try {
			for (String namespace : repository.listNamespaces(projectId, database, supplier(cancelled))) {
				add(data, sink, FirestoreResource.namespace(projectId, database, namespace));
			}
		} catch (FirestoreException e) {
			fail("Datastore namespace list for " + projectId + "/" + database, e);
			return data;
		}
		addKinds(data, sink, node, "", cancelled);
		return data;
	}

	private NuclrResourceData listKinds(GcpResource node, String namespace, AtomicBoolean cancelled, EntrySink sink) {
		var data = start(NODE_COLUMNS, sink);
		add(data, sink, FirestoreResource.parentOf(node));
		addKinds(data, sink, node, namespace, cancelled);
		return data;
	}

	private void addKinds(NuclrResourceData data, EntrySink sink, GcpResource node, String namespace,
			AtomicBoolean cancelled) {
		String projectId = GcpResource.projectId(node);
		String database = FirestoreResource.database(node);
		try {
			for (String kind : repository.listKinds(projectId, database, namespace, supplier(cancelled))) {
				add(data, sink, FirestoreResource.entityKind(projectId, database, namespace, kind));
			}
		} catch (FirestoreException e) {
			fail("Datastore kind list for " + projectId + "/" + database + "/" + namespace, e);
		}
	}

	// -------------------------------------------------------------------------
	// Paged listings: documents of a collection, entities of a kind
	// -------------------------------------------------------------------------

	/** Re-emit the rows loaded so far, then fetch and append one more page. */
	private NuclrResourceData nextPage(GcpResource node, AtomicBoolean cancelled, EntrySink sink) {
		boolean entities = FirestoreResource.is(node, FirestoreResource.KIND_ENTITY_KIND);
		var data = start(entities ? ENTITY_COLUMNS : DOCUMENT_COLUMNS, sink);
		if (pagedRows.isEmpty()) {
			pagedRows.add(FirestoreResource.parentOf(node));
		}
		for (NuclrResource row : pagedRows) {
			add(data, sink, row);
		}
		if (isCancelled(cancelled)) {
			return data;
		}

		String projectId = GcpResource.projectId(node);
		String database = FirestoreResource.database(node);
		FirestorePage<FirestoreDocument> page;
		try {
			page = entities
					? repository.listEntities(projectId, database, FirestoreResource.namespace(node),
							FirestoreResource.entityKind(node), PAGE_SIZE, nextToken)
					: repository.listDocuments(projectId, database, FirestoreResource.path(node), PAGE_SIZE, nextToken);
		} catch (FirestoreException e) {
			fail("Firestore listing " + node.getFullPath(), e);
			return data;
		}

		for (FirestoreDocument document : page.items()) {
			NuclrResource row = entities
					? FirestoreResource.entity(projectId, database, FirestoreResource.namespace(node), document)
					: FirestoreResource.documentJson(projectId, database, document, true);
			pagedRows.add(row);
			add(data, sink, row);
		}
		nextToken = page.hasMore() ? page.nextToken() : null;
		if (nextToken != null) {
			add(data, sink, FirestoreResource.loadMore(node));
		}
		log.info("Firestore listing {}: +{} row(s), more={}", node.getFullPath(), page.items().size(), nextToken != null);
		return data;
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static NuclrResourceData start(List<String> columns, EntrySink sink) {
		var data = new NuclrResourceData();
		data.setColumnNames(columns);
		if (sink != null) {
			sink.columns(columns);
		}
		return data;
	}

	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource entry) {
		data.getEntries().add(entry);
		if (sink != null) {
			sink.add(entry);
		}
	}

	private static void fail(String what, FirestoreException e) {
		log.warn("{} failed: {}", what, e.error());
		GcpErrorDialog.show(e.error());
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	private static BooleanSupplier supplier(AtomicBoolean cancelled) {
		return () -> isCancelled(cancelled);
	}
}
