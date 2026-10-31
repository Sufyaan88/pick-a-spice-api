package com.pickaspice.api;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

public final class ApiTestClient implements AutoCloseable {

	private static final String USERNAME = "category-test-user";
	private static final String PASSWORD = "category-test-password";
	private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

	private final int port;
	private final HttpClient client;

	public ApiTestClient(int port) {
		this.port = port;
		client = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.connectTimeout(Duration.ofSeconds(10)).build();
	}

	public HttpResponse<String> send(String method, String path, String body, boolean authenticated, boolean withCsrf) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.header("Accept", "application/json");
		if (withCsrf) {
			request.header("X-CSRF-TOKEN", csrfToken());
		}
		if (authenticated) {
			String credentials = Base64.getEncoder().encodeToString(
					(USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
			request.header("Authorization", "Basic " + credentials);
		}
		return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	public String csrfToken() throws Exception {
		// Use the real login page until dedicated admin security replaces the default authentication.
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login"))
				.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(200);
		Matcher token = CSRF_INPUT.matcher(login.body());
		assertThat(token.find()).as("Default login page supplies a CSRF token").isTrue();
		return token.group(1);
	}

	public void signIn() throws Exception {
		String form = "username=" + USERNAME + "&password=" + PASSWORD + "&_csrf="
				+ URLEncoder.encode(csrfToken(), StandardCharsets.UTF_8);
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login"))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(302);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	@Override
	public void close() {
		client.close();
	}

}
