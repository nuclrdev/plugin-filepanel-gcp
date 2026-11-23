package dev.nuclr.plugin.core.panel.gcp.firestore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.gcp.GcpError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FirestoreRepositoryTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Replays canned responses and records what was sent. */
	private static final class FakeApi implements FirestoreHttp.Transport, FirestoreHttp.Tokens {
		final Deque<FirestoreHttp.Response> responses = new ArrayDeque<>();
		final List<String> requests = new ArrayList<>();
		final List<String> bodies = new ArrayList<>();
		final List<String> tokensUsed = new ArrayList<>();
		int refreshes;

		FakeApi reply(int status, String body) {
			responses.add(new FirestoreHttp.Response(status, body));
			return this;
		}

		@Override
		public FirestoreHttp.Response send(String method, URI uri, String body, String token) {
			requests.add(method + " " + uri);
			bodies.add(body);
			tokensUsed.add(token);
			return responses.removeFirst();
		}

		@Override
		public String get(boolean refresh) {
			if (refresh) {
				refreshes++;
			}
			return "token-" + refreshes;
		}

		@Override
		public void invalidate() {}

		FirestoreRepository repository() {
			return new FirestoreRepository(new FirestoreHttp(this, this));
		}
	}

	private static String json(String singleQuoted) {
		return singleQuoted.replace('\'', '"');
	}

	@Test
	void listsDatabasesWithTheDefaultFirstAndTheirMode() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'databases': [
				  {'name': 'projects/p/databases/orders', 'type': 'DATASTORE_MODE', 'locationId': 'eur3'},
				  {'name': 'projects/p/databases/(default)', 'type': 'FIRESTORE_NATIVE', 'locationId': 'nam5'}]}
				"""));

		List<FirestoreDatabase> databases = api.repository().listDatabases("p");

		assertEquals(List.of(
				new FirestoreDatabase("(default)", false, "nam5"),
				new FirestoreDatabase("orders", true, "eur3")), databases);
		assertEquals("GET https://firestore.googleapis.com/v1/projects/p/databases", api.requests.get(0));
	}

	@Test
	void listsDocumentsAsPlainJsonAndKeepsMissingOnes() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'documents': [
				  {'name': 'projects/p/databases/(default)/documents/users/alice',
				   'createTime': '2024-05-01T10:20:30.123456Z', 'updateTime': '2024-05-02T11:00:00Z',
				   'fields': {
				     'age': {'integerValue': '42'},
				     'name': {'stringValue': 'Alice'},
				     'score': {'doubleValue': 'NaN'},
				     'tags': {'arrayValue': {'values': [{'stringValue': 'a'}, {'booleanValue': true}]}},
				     'home': {'geoPointValue': {'latitude': 1.5, 'longitude': -2}},
				     'boss': {'referenceValue': 'projects/p/databases/(default)/documents/users/bob'},
				     'address': {'mapValue': {'fields': {'city': {'stringValue': 'Minsk'}, 'zip': {'nullValue': null}}}}}},
				  {'name': 'projects/p/databases/(default)/documents/users/ghost'}],
				 'nextPageToken': 'next/page+1'}
				"""));

		FirestorePage<FirestoreDocument> page = api.repository().listDocuments("p", "(default)", "users", 100, null);

		assertEquals("GET https://firestore.googleapis.com/v1/projects/p/databases/(default)/documents/users"
				+ "?pageSize=100&showMissing=true", api.requests.get(0));
		assertEquals("next/page+1", page.nextToken());
		FirestoreDocument alice = page.items().get(0);
		assertEquals("users/alice", alice.path());
		assertEquals("alice", alice.id());
		assertEquals(7, alice.fieldCount());
		assertEquals("2024-05-01 10:20:30", alice.created());
		assertEquals("2024-05-02 11:00:00", alice.updated());
		assertFalse(alice.missing());
		JsonNode fields = MAPPER.readTree(alice.json());
		assertEquals(42L, fields.path("age").asLong());
		assertTrue(fields.path("age").isNumber());
		assertEquals("Alice", fields.path("name").asText());
		assertEquals("NaN", fields.path("score").asText());
		assertEquals("a", fields.path("tags").path(0).asText());
		assertTrue(fields.path("tags").path(1).asBoolean());
		assertEquals(-2.0, fields.path("home").path("longitude").asDouble());
		assertEquals("projects/p/databases/(default)/documents/users/bob", fields.path("boss").asText());
		assertEquals("Minsk", fields.path("address").path("city").asText());
		assertTrue(fields.path("address").path("zip").isNull());

		FirestoreDocument ghost = page.items().get(1);
		assertTrue(ghost.missing());
		assertEquals("{ }", ghost.json().replaceAll("\\s+", " "));
	}

	@Test
	void sendsThePageTokenEncodedAndEncodesPathSegments() throws Exception {
		var api = new FakeApi().reply(200, "{}");

		FirestorePage<FirestoreDocument> page = api.repository()
				.listDocuments("p", "named", "users/al ice/orders", 50, "a+b/c");

		assertEquals("GET https://firestore.googleapis.com/v1/projects/p/databases/named/documents/users/al%20ice/orders"
				+ "?pageSize=50&showMissing=true&pageToken=a%2Bb%2Fc", api.requests.get(0));
		assertTrue(page.items().isEmpty());
		assertFalse(page.hasMore());
	}

	@Test
	void readsCollectionIdsAcrossPages() throws Exception {
		var api = new FakeApi()
				.reply(200, json("{'collectionIds': ['a', 'b'], 'nextPageToken': 't1'}"))
				.reply(200, json("{'collectionIds': ['c']}"));

		List<String> ids = api.repository().listCollectionIds("p", "(default)", "users/alice", null);

		assertEquals(List.of("a", "b", "c"), ids);
		assertEquals("POST https://firestore.googleapis.com/v1/projects/p/databases/(default)/documents/users/alice"
				+ ":listCollectionIds", api.requests.get(0));
		assertTrue(api.bodies.get(1).contains("\"pageToken\":\"t1\""));
	}

	@Test
	void aMissingDocumentIsNullNotAnError() throws Exception {
		var api = new FakeApi().reply(404, json("{'error': {'message': 'not found'}}"));

		assertNull(api.repository().getDocument("p", "(default)", "users/ghost"));
	}

	@Test
	void refreshesTheTokenOnceOn401() throws Exception {
		var api = new FakeApi().reply(401, "").reply(200, json("{'databases': []}"));

		assertTrue(api.repository().listDatabases("p").isEmpty());
		assertEquals(List.of("token-0", "token-1"), api.tokensUsed);
	}

	@Test
	void showsGooglesExplanationOfAFailure() {
		var api = new FakeApi().reply(403, json(
				"{'error': {'code': 403, 'message': 'Cloud Firestore API has not been used in project p'}}"));

		FirestoreException e = assertThrows(FirestoreException.class, () -> api.repository().listDatabases("p"));

		assertEquals(403, e.status());
		GcpError.CommandFailed failed = assertInstanceOf(GcpError.CommandFailed.class, e.error());
		assertEquals("HTTP 403: Cloud Firestore API has not been used in project p", failed.stderr());
	}

	@Test
	void listsKindsWithoutBuiltInOnesAndLeavesTheDefaultDatabaseIdOut() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'batch': {'entityResults': [
				  {'entity': {'key': {'path': [{'kind': '__kind__', 'name': 'Task'}]}}},
				  {'entity': {'key': {'path': [{'kind': '__kind__', 'name': '__Stat_Total__'}]}}}],
				 'endCursor': 'c1', 'moreResults': 'NO_MORE_RESULTS'}}
				"""));

		assertEquals(List.of("Task"), api.repository().listKinds("p", "(default)", "", null));
		assertEquals("POST https://datastore.googleapis.com/v1/projects/p:runQuery", api.requests.get(0));
		JsonNode body = MAPPER.readTree(api.bodies.get(0));
		assertFalse(body.has("databaseId"));
		assertFalse(body.path("partitionId").has("namespaceId"));
		assertEquals("__kind__", body.path("query").path("kind").path(0).path("name").asText());
	}

	@Test
	void listsNamedNamespacesOnly() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'batch': {'entityResults': [
				  {'entity': {'key': {'path': [{'kind': '__namespace__', 'id': '1'}]}}},
				  {'entity': {'key': {'path': [{'kind': '__namespace__', 'name': 'tenant-a'}]}}}],
				 'moreResults': 'NO_MORE_RESULTS'}}
				"""));

		assertEquals(List.of("tenant-a"), api.repository().listNamespaces("p", "orders", null));
		JsonNode body = MAPPER.readTree(api.bodies.get(0));
		assertEquals("orders", body.path("databaseId").asText());
		assertEquals("orders", body.path("partitionId").path("databaseId").asText());
	}

	@Test
	void pagesEntitiesWithTheQueryCursor() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'batch': {'entityResults': [
				  {'entity': {'key': {'path': [{'kind': 'Order', 'id': '42'}]},
				              'properties': {'total': {'integerValue': '7'},
				                             'customer': {'keyValue': {'path': [{'kind': 'User', 'name': 'alice'}]}}}},
				   'updateTime': '2024-01-01T00:00:00Z'}],
				 'endCursor': 'cur2', 'moreResults': 'MORE_RESULTS_AFTER_LIMIT'}}
				"""));

		FirestorePage<FirestoreDocument> page = api.repository().listEntities("p", "(default)", "tenant-a", "Order", 100, "cur1");

		JsonNode body = MAPPER.readTree(api.bodies.get(0));
		assertEquals("cur1", body.path("query").path("startCursor").asText());
		assertEquals(100, body.path("query").path("limit").asInt());
		assertEquals("tenant-a", body.path("partitionId").path("namespaceId").asText());
		assertEquals("cur2", page.nextToken());
		FirestoreDocument order = page.items().get(0);
		assertEquals("42", order.id());
		assertEquals("Order/42", order.path());
		JsonNode properties = MAPPER.readTree(order.json());
		assertEquals(7, properties.path("total").asInt());
		assertEquals("User/alice", properties.path("customer").asText());
	}

	@Test
	void stopsPagingEntitiesWhenTheQueryIsDone() throws Exception {
		var api = new FakeApi().reply(200, json("""
				{'batch': {'entityResults': [{'entity': {'key': {'path': [{'kind': 'Order', 'id': '1'}]}}}],
				 'endCursor': 'cur2', 'moreResults': 'NO_MORE_RESULTS'}}
				"""));

		assertFalse(api.repository().listEntities("p", "(default)", "", "Order", 100, null).hasMore());
	}

	@Test
	void splitsPaths() {
		assertEquals("orders", FirestoreRepository.lastSegment("users/alice/orders"));
		assertEquals("users/alice", FirestoreRepository.parentPath("users/alice/orders"));
		assertEquals("", FirestoreRepository.parentPath("users"));
		assertEquals("-", FirestoreRepository.time(""));
	}
}
