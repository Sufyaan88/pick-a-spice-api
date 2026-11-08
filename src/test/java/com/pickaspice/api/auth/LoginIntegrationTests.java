package com.pickaspice.api.auth;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.pickaspice.api.TestDatabaseConfiguration;
import com.pickaspice.api.ApiTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "server.servlet.session.timeout=1m")
@Import(TestDatabaseConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginIntegrationTests {

	@Autowired
	private AdminAccountService accounts;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ObjectMapper json;
	@Value("${local.server.port}")
	private int port;
	private ApiTestClient client;

	@BeforeEach
	void prepare() {
		jdbc.update("delete from users");
		client = new ApiTestClient(port);
	}

	@AfterEach
	void clean() {
		try {
			jdbc.update("delete from users");
		}
		finally {
			client.close();
		}
	}

	@Test
	void suppliesCsrfTokenToAnonymousBrowser() throws Exception {
		// Arrange
		String path = "/api/auth/csrf";
		// Act
		HttpResponse<String> response = request("GET", path, "", null);
		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("token").asText()).isNotBlank();
		assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
	}

	@Test
	void logsInUsingNormalizedEmailAndReusesSessionForAdminRequests() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		String originalSession = sessionCookie();

		// Act
		HttpResponse<String> login = login(" OWNER@EXAMPLE.TEST ", ADMIN_PASSWORD, token);
		HttpResponse<String> me = request("GET", "/api/auth/me", "", null);
		HttpResponse<String> catalogue = request("GET", "/api/admin/products", "", null);

		// Assert
		assertThat(login.statusCode()).isEqualTo(204);
		assertThat(login.body()).isEmpty();
		assertThat(sessionCookie()).isNotEqualTo(originalSession);
		assertThat(login.headers().allValues("Set-Cookie").toString()).contains("HttpOnly").contains("SameSite=Lax");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(json.readTree(me.body()).propertyNames()).containsExactlyInAnyOrder("id", "email", "role");
		assertThat(json.readTree(me.body()).path("email").asText()).isEqualTo(ADMIN_EMAIL);
		assertThat(json.readTree(me.body()).path("role").asText()).isEqualTo("ADMIN");
		assertThat(catalogue.statusCode()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"unknown", "wrong-password", "inactive"})
	void rejectsUnknownWrongAndInactiveAccountsIdentically(String scenario) throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		if (scenario.equals("inactive")) {
			jdbc.update("update users set active=false where email=?", ADMIN_EMAIL);
		}
		String email = scenario.equals("unknown") ? "unknown@example.test" : ADMIN_EMAIL;
		String password = scenario.equals("wrong-password") ? "an incorrect password" : ADMIN_PASSWORD;
		var usersBefore = jdbc.queryForList("select id,email,password_hash,active,role from users");
		String token = csrf();

		// Act
		HttpResponse<String> response = login(email, password, token);
		HttpResponse<String> me = request("GET", "/api/auth/me", "", null);

		// Assert
		assertLoginRejected(response);
		assertThat(me.statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForList("select id,email,password_hash,active,role from users")).isEqualTo(usersBefore);
	}

	@Test
	void loginRequiresCsrfAndDoesNotCountRejectedRequestsAsPasswordFailures() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);

		// Act
		HttpResponse<String> response = login(ADMIN_EMAIL, ADMIN_PASSWORD, null);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(failures()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{", "{\"email\":123,\"password\":true}",
			"{\"email\":\"owner@example.test\",\"password\":null}",
			"{\"email\":\"owner@example.test\",\"password\":\"\"}",
			"{\"email\":\"owner@example.test\",\"password\":\"some password\"} {}"})
	void rejectsMalformedCredentialsWithoutRecordingFailures(String body) throws Exception {
		// Arrange
		String token = csrf();

		// Act
		HttpResponse<String> response = request("POST", "/api/auth/login", body, token);

		// Assert
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(failures()).isZero();
	}

	@Test
	void rejectsOversizedLoginBodyWithoutRecordingFailures() throws Exception {
		// Arrange
		String body = json.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "x".repeat(4096)));
		String token = csrf();

		// Act
		HttpResponse<String> response = request("POST", "/api/auth/login", body, token);

		// Assert
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(failures()).isZero();
	}

	@Test
	void fifthFailureLocksAccountAndCorrectPasswordCannotBypassLock() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		for (int index=0; index<4; index++) {
			login(ADMIN_EMAIL, "incorrect password", token);
		}
		Instant beforeFifth = Instant.now();

		// Act
		HttpResponse<String> fifth = login(ADMIN_EMAIL, "incorrect password", token);
		var locked = jdbc.queryForMap("select locked_until from users where email=?", ADMIN_EMAIL);
		HttpResponse<String> correct = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);

		// Assert
		assertLoginRejected(fifth);
		assertLoginRejected(correct);
		assertThat(locked.get("locked_until")).isNotNull();
		Instant lockedUntil = jdbc.queryForObject("select locked_until from users where email=?",
				(row, index) -> row.getTimestamp(1).toInstant(), ADMIN_EMAIL);
		assertThat(lockedUntil).isBetween(beforeFifth.plus(Duration.ofMinutes(15)), Instant.now().plus(Duration.ofMinutes(15)));
		assertThat(jdbc.queryForMap("select locked_until from users where email=?", ADMIN_EMAIL)).isEqualTo(locked);
	}

	@Test
	void successfulLoginBeforeFifthFailureClearsHistory() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		for (int index=0; index<4; index++) {
			login(ADMIN_EMAIL, "incorrect password", token);
		}

		// Act
		HttpResponse<String> success = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);

		// Assert
		assertThat(success.statusCode()).isEqualTo(204);
		assertThat(failures()).isZero();
	}

	@Test
	void failuresOutsideRollingWindowDoNotTriggerLock() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		for (int index=0; index<4; index++) {
			login(ADMIN_EMAIL, "incorrect password", token);
		}
		jdbc.update("update login_failures set attempted_at = current_timestamp - interval '16 minutes'");

		// Act
		HttpResponse<String> failure = login(ADMIN_EMAIL, "incorrect password", token);
		HttpResponse<String> success = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);

		// Assert
		assertLoginRejected(failure);
		assertThat(success.statusCode()).isEqualTo(204);
	}

	@Test
	void expiredLockAllowsLoginAgain() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		for (int index=0; index<5; index++) {
			login(ADMIN_EMAIL, "incorrect password", token);
		}
		jdbc.update("update users set locked_until=current_timestamp - interval '1 second' where email=?", ADMIN_EMAIL);

		// Act
		HttpResponse<String> response = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);

		// Assert
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(jdbc.queryForObject("select locked_until is null from users where email=?", Boolean.class, ADMIN_EMAIL)).isTrue();
		assertThat(failures()).isZero();
	}

	@Test
	void concurrentFailuresCannotBypassLockout() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		List<Future<HttpResponse<String>>> responses = new ArrayList<>();
		List<HttpResponse<String>> completed = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(5)) {

			// Act
			for (int index=0; index<5; index++) {
				responses.add(executor.submit(() -> login(ADMIN_EMAIL, "incorrect password", token)));
			}
			for (var response : responses) {
				completed.add(response.get(30, TimeUnit.SECONDS));
			}
		}
		HttpResponse<String> correct = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);

		// Assert
		for (var response : completed) {
			assertLoginRejected(response);
		}
		assertLoginRejected(correct);
	}

	@Test
	void lockoutDoesNotEndExistingSessionOrBlockOtherAccount() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		accounts.create("second@example.test", ADMIN_PASSWORD);
		login(ADMIN_EMAIL, ADMIN_PASSWORD, csrf());
		try (ApiTestClient attacker = new ApiTestClient(port)) {
			String token = json.readTree(send(attacker,"GET","/api/auth/csrf","",null).body()).path("token").asText();
			String badBody = json.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect password"));

			// Act
			for (int index=0; index<5; index++) {
				send(attacker,"POST","/api/auth/login",badBody,token);
			}
			HttpResponse<String> me = request("GET", "/api/auth/me", "", null);
			String otherBody = json.writeValueAsString(Map.of("email", "second@example.test", "password", ADMIN_PASSWORD));
			HttpResponse<String> other = send(attacker,"POST","/api/auth/login",otherBody,token);

			// Assert
			assertThat(me.statusCode()).isEqualTo(200);
			assertThat(other.statusCode()).isEqualTo(204);
		}
	}

	@Test
	void logoutRequiresCsrfAndInvalidatesSessionAndOldToken() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String oldToken = csrf();
		login(ADMIN_EMAIL, ADMIN_PASSWORD, oldToken);
		String token = csrf();
		String authenticatedSession = sessionCookie();

		// Act
		HttpResponse<String> stale = request("POST", "/api/auth/logout", "", oldToken);
		HttpResponse<String> missing = request("POST", "/api/auth/logout", "", null);
		HttpResponse<String> logout = request("POST", "/api/auth/logout", "", token);
		HttpResponse<String> me = request("GET", "/api/auth/me", "", null);
		HttpResponse<String> replay = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);
		HttpResponse<String> sessionReplay;
		try (HttpClient replayClient = HttpClient.newHttpClient()) {
			sessionReplay = replayClient.send(HttpRequest.newBuilder(uri("/api/auth/me"))
					.header("Cookie", "JSESSIONID=" + authenticatedSession).GET().build(), HttpResponse.BodyHandlers.ofString());
		}

		// Assert
		assertThat(stale.statusCode()).isEqualTo(403);
		assertThat(missing.statusCode()).isEqualTo(403);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.body()).isEmpty();
		assertThat(me.statusCode()).isEqualTo(401);
		assertThat(replay.statusCode()).isEqualTo(403);
		assertThat(sessionReplay.statusCode()).isEqualTo(401);
	}

	@Test
	void failedReloginClearsPreviousAuthentication() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		login(ADMIN_EMAIL, ADMIN_PASSWORD, csrf());
		String token = csrf();

		// Act
		HttpResponse<String> failed = login(ADMIN_EMAIL, "incorrect password", token);
		HttpResponse<String> me = request("GET", "/api/auth/me", "", null);

		// Assert
		assertLoginRejected(failed);
		assertThat(me.statusCode()).isEqualTo(401);
	}

	@Test
	void doesNotLogPasswordsTokensOrSessionIdentifiers(CapturedOutput output) throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String token = csrf();
		String parserMarker = "private-credential-marker";

		// Act
		HttpResponse<String> success = login(ADMIN_EMAIL, ADMIN_PASSWORD, token);
		String session = sessionCookie();
		HttpResponse<String> invalid = request("POST", "/api/auth/login", "{\"password\":" + parserMarker + "}", csrf());

		// Assert
		assertThat(success.statusCode()).isEqualTo(204);
		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD, token, session, parserMarker);
	}

	@Test
	void rejectsBasicAuthenticationAndAnonymousMaintenanceAccess() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String basic = java.util.Base64.getEncoder().encodeToString((ADMIN_EMAIL+":"+ADMIN_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(uri("/api/admin/products")).header("Authorization", "Basic " + basic).GET().build();

		// Act
		HttpResponse<String> response;
		try (HttpClient basicClient = HttpClient.newHttpClient()) {
			response = basicClient.send(request, HttpResponse.BodyHandlers.ofString());
		}
		HttpResponse<String> me = this.request("GET", "/api/auth/me", "", null);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(me.statusCode()).isEqualTo(401);
	}

	@Test
	void sessionExpiresAfterInactivity() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		login(ADMIN_EMAIL, ADMIN_PASSWORD, csrf());
		// Tomcat uses whole minutes. Exercise real expiry with a one-minute test timeout.
		Thread.sleep(Duration.ofSeconds(65));

		// Act
		HttpResponse<String> response = request("GET", "/api/auth/me", "", null);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> login(String email, String password, String token) throws Exception {
		return client.login(email, password, token);
	}

	private String csrf() throws Exception {
		HttpResponse<String> response = request("GET", "/api/auth/csrf", "", null);
		assertThat(response.statusCode()).isEqualTo(200);
		return json.readTree(response.body()).path("token").asText();
	}

	private String sessionCookie() {
		return client.sessionCookie();
	}

	private long failures() {
		return jdbc.queryForObject("select count(*) from login_failures", Long.class);
	}

	private void assertLoginRejected(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Unable to sign in with those credentials.");
	}

	private URI uri(String path) {
		return URI.create("http://localhost:"+port+path);
	}

	private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		return send(client,method,path,body,token);
	}

	private HttpResponse<String> send(ApiTestClient http, String method, String path, String body, String token) throws Exception {
		return http.request(method, path, body, token);
	}
}
