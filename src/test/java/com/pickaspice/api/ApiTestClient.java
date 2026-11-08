package com.pickaspice.api;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

public final class ApiTestClient implements AutoCloseable {

	private final int port;
	private final ObjectMapper json = new ObjectMapper();
	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	private final HttpClient client;
	private final HttpClient anonymous;
	private boolean signedIn;

	public ApiTestClient(int port) {
		this.port = port;
		client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
		anonymous = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.connectTimeout(Duration.ofSeconds(10)).build();
	}

	public HttpResponse<String> send(String method, String path, String body, boolean authenticated, boolean withCsrf) throws Exception {
		if (authenticated) {
			ensureSignedIn();
		}
		HttpClient selected = authenticated ? client : anonymous;
		return send(selected, method, path, body, withCsrf ? csrfToken(selected) : null);
	}

	public HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		return send(client, method, path, body, token);
	}

	public HttpResponse<String> login(String email, String password, String token) throws Exception {
		return request("POST", "/api/auth/login", json.writeValueAsString(Map.of("email", email, "password", password)), token);
	}

	public String csrfToken() throws Exception {
		return csrfToken(client);
	}

	public void signIn() throws Exception {
		HttpResponse<String> response = login(ADMIN_EMAIL, ADMIN_PASSWORD, csrfToken());
		assertThat(response.statusCode()).isEqualTo(204);
		signedIn = true;
	}

	public String sessionCookie() {
		return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
				.findFirst().orElseThrow().getValue();
	}

	private synchronized void ensureSignedIn() throws Exception {
		if (!signedIn) {
			signIn();
		}
	}

	private String csrfToken(HttpClient selected) throws Exception {
		HttpResponse<String> response = send(selected, "GET", "/api/auth/csrf", "", null);
		assertThat(response.statusCode()).isEqualTo(200);
		return json.readTree(response.body()).path("token").asText();
	}

	private HttpResponse<String> send(HttpClient selected, String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("Accept", "application/json");
		if (token != null) {
			request.header("X-CSRF-TOKEN", token);
		}
		return selected.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	@Override
	public void close() {
		try {
			client.close();
		}
		finally {
			anonymous.close();
		}
	}
}
