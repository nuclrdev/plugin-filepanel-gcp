package dev.nuclr.plugin.core.panel.gcp.firestore;

import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the typed values of the Firestore and Datastore REST APIs into plain JSON, so a document reads
 * as {@code {"age": 42}} rather than {@code {"age": {"integerValue": "42"}}}.
 *
 * <p>Firestore wraps values as {@code mapValue.fields} and {@code referenceValue}; Datastore as
 * {@code entityValue.properties}, {@code keyValue} and {@code blobValue}. Both share the scalar and array
 * shapes. Timestamps, references, keys and bytes become strings; integers (sent as strings) become numbers.
 */
public final class FirestoreValues {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private FirestoreValues() {}

	/** A Firestore {@code fields} or Datastore {@code properties} object as plain JSON. */
	public static ObjectNode plainObject(JsonNode fields) {
		ObjectNode out = NODES.objectNode();
		if (fields == null || !fields.isObject()) {
			return out;
		}
		for (Map.Entry<String, JsonNode> field : fields.properties()) {
			out.set(field.getKey(), plain(field.getValue()));
		}
		return out;
	}

	/** One typed value as plain JSON; an unknown shape is kept as it came. */
	static JsonNode plain(JsonNode value) {
		if (value == null || !value.isObject()) {
			return NODES.nullNode();
		}
		if (value.has("nullValue")) {
			return NODES.nullNode();
		}
		if (value.has("booleanValue")) {
			return NODES.booleanNode(value.path("booleanValue").asBoolean());
		}
		if (value.has("integerValue")) {
			String text = value.path("integerValue").asText("");
			try {
				return NODES.numberNode(Long.parseLong(text));
			} catch (NumberFormatException e) {
				return NODES.stringNode(text);
			}
		}
		if (value.has("doubleValue")) {
			JsonNode number = value.path("doubleValue");
			// NaN and the infinities arrive as strings; JSON has no number for them.
			return number.isNumber() ? NODES.numberNode(number.asDouble()) : NODES.stringNode(number.asText(""));
		}
		for (String key : new String[] {"stringValue", "timestampValue", "referenceValue", "bytesValue", "blobValue"}) {
			if (value.has(key)) {
				return NODES.stringNode(value.path(key).asText(""));
			}
		}
		if (value.has("geoPointValue")) {
			JsonNode point = value.path("geoPointValue");
			ObjectNode out = NODES.objectNode();
			out.put("latitude", point.path("latitude").asDouble());
			out.put("longitude", point.path("longitude").asDouble());
			return out;
		}
		if (value.has("arrayValue")) {
			ArrayNode out = NODES.arrayNode();
			for (JsonNode item : value.path("arrayValue").path("values")) {
				out.add(plain(item));
			}
			return out;
		}
		if (value.has("mapValue")) {
			return plainObject(value.path("mapValue").path("fields"));
		}
		if (value.has("entityValue")) {
			return plainObject(value.path("entityValue").path("properties"));
		}
		if (value.has("keyValue")) {
			return NODES.stringNode(keyPath(value.path("keyValue")));
		}
		return value;
	}

	/** A Datastore key as {@code Kind/name/Kind/id}, e.g. {@code Task/sample} or {@code Order/42}. */
	public static String keyPath(JsonNode key) {
		var out = new StringBuilder();
		for (JsonNode element : key.path("path")) {
			if (!out.isEmpty()) {
				out.append('/');
			}
			out.append(element.path("kind").asText("")).append('/').append(keyId(element));
		}
		return out.toString();
	}

	/** The id of one key path element: its name, else its numeric id (sent as a string). */
	public static String keyId(JsonNode element) {
		String name = element.path("name").asText("");
		return name.isEmpty() ? element.path("id").asText("") : name;
	}

	public static String pretty(JsonNode node) {
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
	}
}
