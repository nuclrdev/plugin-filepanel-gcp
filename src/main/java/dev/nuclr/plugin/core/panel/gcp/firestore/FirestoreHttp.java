package dev.nuclr.plugin.core.panel.gcp.firestore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import dev.nuclr.plugin.core.panel.gcp.GcpError;
import dev.nuclr.plugin.core.panel.gcp.gcs.GcsAccessToken;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends JSON requests to the Firestore and Datastore REST APIs with the gcloud access token shared with
 * GCS ({@link GcsAccessToken}), refreshing it once on a 401. No Swing.
 */
@Slf4j
public class FirestoreHttp {

	/** Sends one request; swapped out in tests. */
	public interface Transport {
		Response send(String method, URI uri, String body, String token) throws IOException, InterruptedException;
	}

	/** Supplies the bearer token; swapped out in tests. */
	public interface Tokens {
		String get(boolean refresh) throws IOException;

		void invalidate();
	}

	public record Response(int status, String body) {}

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final ObjectMapper mapper = new ObjectMapper();
	private final Transport transport;
	private final Tokens tokens;

	public FirestoreHttp() {
		this(FirestoreHttp::sendHttp, new Tokens() {
			@Override
			public String get(boolean refresh) throws IOException {
				return GcsAccessToken.get(refresh);
			}

			@Override
			public void invalidate() {
				GcsAccessToken.invalidate();
			}
		});
	}

	public FirestoreHttp(Transport transport, Tokens tokens) {
		this.transport = transport;
		this.tokens = tokens;
	}

	public JsonNode get(URI uri) throws FirestoreException {
		return send("GET", uri, null);
	}

	public JsonNode post(URI uri, JsonNode body) throws FirestoreException {
		return send("POST", uri, body == null ? "{}" : body.toString());
	}

	private JsonNode send(String method, URI uri, String body) throws FirestoreException {
		for (int attempt = 0; attempt < 2; attempt++) {
			String token;
			try {
				token = tokens.get(attempt == 1);
			} catch (IOException e) {
				throw new FirestoreException(new GcpError.CommandFailed(e.getMessage()), 0);
			}
			long startNanos = System.nanoTime();
			Response response;
			try {
				response = transport.send(method, uri, body, token);
			} catch (IOException e) {
				throw new FirestoreException(new GcpError.CommandFailed(e.getMessage()), 0);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FirestoreException(new GcpError.Cancelled(), 0);
			}
			int status = response.status();
			log.info("{} {}: HTTP {} in {} ms", method, uri.getPath(), status, (System.nanoTime() - startNanos) / 1_000_000L);
			if (status == 401 && attempt == 0) {
				tokens.invalidate(); // token expired mid-session; refresh and retry once
				continue;
			}
			if (status / 100 != 2) {
				throw new FirestoreException(classify(status, response.body()), status);
			}
			try {
				String text = response.body();
				return mapper.readTree(text == null || text.isBlank() ? "{}" : text);
			} catch (JacksonException e) {
				throw new FirestoreException(new GcpError.CommandFailed("Unreadable response: " + e.getOriginalMessage()), status);
			}
		}
		throw new FirestoreException(new GcpError.NotAuthenticated(), 401);
	}

	/** Google APIs explain a failure in {@code error.message} (e.g. the API is disabled for the project). */
	GcpError classify(int status, String body) {
		if (status == 401) {
			return new GcpError.NotAuthenticated();
		}
		String message = "";
		try {
			message = mapper.readTree(body == null || body.isBlank() ? "{}" : body).path("error").path("message").asText("");
		} catch (JacksonException ignored) {
			// not JSON; fall back to the raw body below
		}
		if (message.isBlank() && body != null) {
			message = body.strip();
		}
		if (message.length() > 500) {
			message = message.substring(0, 500);
		}
		return new GcpError.CommandFailed("HTTP " + status + (message.isBlank() ? "" : ": " + message));
	}

	private static Response sendHttp(String method, URI uri, String body, String token)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + token);
		if (body == null) {
			request.GET();
		} else {
			request.header("Content-Type", "application/json")
					.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		}
		HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(response.statusCode(), response.body());
	}
}
