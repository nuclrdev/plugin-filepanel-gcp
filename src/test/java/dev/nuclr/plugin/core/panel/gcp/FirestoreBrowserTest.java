package dev.nuclr.plugin.core.panel.gcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreDocument;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreHttp;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestorePage;
import dev.nuclr.plugin.core.panel.gcp.firestore.FirestoreRepository;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;

class FirestoreBrowserTest {

	/** Serves documents of any collection in pages of two, out of five. */
	private static final class FakeRepository extends FirestoreRepository {
		final List<String> tokensAsked = new ArrayList<>();

		FakeRepository() {
			super(new FirestoreHttp((method, uri, body, token) -> {
				throw new AssertionError("no HTTP expected");
			}, null));
		}

		@Override
		public FirestorePage<FirestoreDocument> listDocuments(String projectId, String database, String collectionPath,
				int pageSize, String pageToken) {
			tokensAsked.add(pageToken);
			int from = pageToken == null ? 0 : Integer.parseInt(pageToken);
			var items = new ArrayList<FirestoreDocument>();
			for (int i = from; i < Math.min(from + 2, 5); i++) {
				items.add(new FirestoreDocument(collectionPath + "/d" + i, "d" + i, "{\"n\": " + i + "}", 1, "-", "-", false));
			}
			return new FirestorePage<>(items, from + 2 < 5 ? String.valueOf(from + 2) : null);
		}

		@Override
		public List<String> listCollectionIds(String projectId, String database, String documentPath,
				BooleanSupplier cancelled) {
			return List.of("orders");
		}

		@Override
		public FirestoreDocument getDocument(String projectId, String database, String documentPath) {
			return null; // exists only through its subcollections
		}
	}

	private static List<String> names(NuclrResourceData data) {
		return data.getEntries().stream().map(NuclrResource::getName).toList();
	}

	private static NuclrResource last(NuclrResourceData data) {
		return data.getEntries().get(data.getEntries().size() - 1);
	}

	@Test
	void loadMoreAppendsTheNextPageUntilTheCollectionEnds() {
		var repository = new FakeRepository();
		var browser = new FirestoreBrowser(repository);

		NuclrResourceData first = browser.open(FirestoreResource.collection("p", "(default)", "users"), null, null);
		assertEquals(List.of("..", "d0.json", "d1.json"), names(first).subList(0, 3));
		assertEquals(4, first.getEntries().size());
		assertTrue(FirestoreResource.is(last(first), FirestoreResource.KIND_LOAD_MORE));

		NuclrResourceData second = browser.open(last(first), null, null);
		NuclrResourceData third = browser.open(last(second), null, null);

		assertEquals(List.of("..", "d0.json", "d1.json", "d2.json", "d3.json", "d4.json"), names(third));
		assertEquals(java.util.Arrays.asList(null, "2", "4"), repository.tokensAsked);
		assertEquals(FirestoreResource.collection("p", "(default)", "users").getUuid(),
				browser.location(last(second)).getUuid());
	}

	@Test
	void loadMoreAfterLeavingStartsTheListingOver() {
		var repository = new FakeRepository();
		var browser = new FirestoreBrowser(repository);
		NuclrResourceData first = browser.open(FirestoreResource.collection("p", "(default)", "users"), null, null);

		browser.reset();
		NuclrResourceData again = browser.open(last(first), null, null);

		assertEquals("d0.json", again.getEntries().get(1).getName());
	}

	@Test
	void aMissingDocumentShowsOnlyItsSubcollections() {
		var browser = new FirestoreBrowser(new FakeRepository());

		NuclrResourceData data = browser.open(FirestoreResource.document("p", "(default)", "users/ghost"), null, null);

		assertEquals(List.of("..", "orders"), names(data));
		NuclrResource orders = data.getEntries().get(1);
		assertEquals("users/ghost/orders", FirestoreResource.path(orders));
		assertTrue(orders.isFolder());
	}

	@Test
	void parentsLeadBackUpTheTree() {
		NuclrResource sub = FirestoreResource.collection("p", "(default)", "users/alice/orders");
		NuclrResource doc = FirestoreResource.parentOf(sub);
		assertEquals("..", doc.getName());
		assertTrue(FirestoreResource.is(doc, FirestoreResource.KIND_DOCUMENT));
		assertEquals("users/alice", FirestoreResource.path(doc));

		NuclrResource users = FirestoreResource.parentOf(doc);
		assertEquals("users", FirestoreResource.path(users));

		NuclrResource database = FirestoreResource.parentOf(users);
		assertTrue(FirestoreResource.is(database, FirestoreResource.KIND_DATABASE));

		NuclrResource service = FirestoreResource.parentOf(database);
		assertEquals(GcpResource.SERVICE_FIRESTORE, GcpResource.serviceType(service));

		NuclrResource kind = FirestoreResource.entityKind("p", "orders", "tenant-a", "Order");
		assertTrue(FirestoreResource.is(FirestoreResource.parentOf(kind), FirestoreResource.KIND_NAMESPACE));
		assertTrue(FirestoreResource.isDatastore(FirestoreResource.parentOf(kind)));
	}

	@Test
	void aDocumentLeafStreamsItsFieldsAndKnowsWhereEnterLeads() throws Exception {
		var document = new FirestoreDocument("users/alice", "alice", "{\"age\": 42}", 1, "-", "-", false);
		NuclrResource inCollection = FirestoreResource.documentJson("p", "(default)", document, true);
		NuclrResource inDocument = FirestoreResource.documentJson("p", "(default)", document, false);

		try (InputStream in = inCollection.openInputStream()) {
			assertEquals("{\"age\": 42}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertEquals(11, inCollection.getLength());
		assertFalse(inCollection.isFolder());
		assertTrue(FirestoreResource.opensDocument(inCollection));
		assertFalse(FirestoreResource.opensDocument(inDocument));
		assertEquals("https://console.cloud.google.com/firestore/databases/-default-/data/panel/users/alice?project=p",
				GcpResource.consoleUrl(inDocument));
	}
}
