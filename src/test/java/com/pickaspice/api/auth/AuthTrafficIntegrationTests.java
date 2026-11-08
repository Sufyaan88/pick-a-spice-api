package com.pickaspice.api.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.auth.login-attempts-per-window=2", "app.auth.new-sessions-per-window=3",
		"app.auth.traffic-window=2s", "app.auth.concurrent-logins=1"})
@Import(TestDatabaseConfiguration.class)
class AuthTrafficIntegrationTests {
	@Autowired
	private AdminAccountService accounts;
	@Autowired
	private JdbcTemplate jdbc;
	@Value("${local.server.port}")
	private int port;

	@BeforeEach
	void prepare() throws Exception {
		// Real elapsed time resets the production limiter between independent tests.
		Thread.sleep(Duration.ofMillis(2100));
		jdbc.update("delete from users");
	}

	@AfterEach
	void clean() {
		jdbc.update("delete from users");
	}

	@Test
	void throttlesUnknownAccountsWithoutWritingFailuresAndRecoversAfterWindow() throws Exception {
		// Arrange
		try (var client = new ApiTestClient(port)) {
			String token = client.csrfToken();

			// Act
			var first = client.login("unknown@example.test", ADMIN_PASSWORD, token);
			var second = client.login("unknown@example.test", ADMIN_PASSWORD, token);
			var rejected = client.login("another@example.test", ADMIN_PASSWORD, token);
			Thread.sleep(Duration.ofMillis(2100));
			var recovered = client.login("unknown@example.test", ADMIN_PASSWORD, token);

			// Assert
			assertThat(first.statusCode()).isEqualTo(401);
			assertThat(second.statusCode()).isEqualTo(401);
			assertThat(rejected.statusCode()).isEqualTo(429);
			assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
			assertThat(rejected.body()).doesNotContain(ADMIN_PASSWORD, token, "another@example.test");
			assertThat(recovered.statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("select count(*) from login_failures", Long.class)).isZero();
		}
	}

	@Test
	void rejectedAttemptsDoNotCheckPasswordsOrChangeExistingAuthentication() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = new ApiTestClient(port)) {
			client.signIn();
			String token = client.csrfToken();
			client.login(ADMIN_EMAIL, ADMIN_PASSWORD, token);
			token = client.csrfToken();

			// Act
			var rejected = client.login(ADMIN_EMAIL, "incorrect password", token);
			var me = client.request("GET", "/api/auth/me", "", null);

			// Assert
			assertThat(rejected.statusCode()).isEqualTo(429);
			assertThat(me.statusCode()).isEqualTo(200);
			assertThat(jdbc.queryForObject("select count(*) from login_failures", Long.class)).isZero();
		}
	}

	@Test
	void boundsNewSessionsButReusesExistingSessionsAndIgnoresSpoofedAddresses() throws Exception {
		// Arrange
		try (var existing = new ApiTestClient(port); var raw = HttpClient.newHttpClient()) {
			String token = existing.csrfToken();
			var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET();

			// Act
			var first = raw.send(request.build(), HttpResponse.BodyHandlers.ofString());
			var second = raw.send(request.build(), HttpResponse.BodyHandlers.ofString());
			var rejected = raw.send(request.header("X-Forwarded-For", "203.0.113.99")
					.header("Forwarded", "for=203.0.113.98").build(), HttpResponse.BodyHandlers.ofString());
			var reused = existing.request("GET", "/api/auth/csrf", "", null);
			Thread.sleep(Duration.ofMillis(2100));
			var recovered = raw.send(request.build(), HttpResponse.BodyHandlers.ofString());

			// Assert
			assertThat(first.statusCode()).isEqualTo(200);
			assertThat(second.statusCode()).isEqualTo(200);
			assertThat(rejected.statusCode()).isEqualTo(429);
			assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
			assertThat(reused.statusCode()).isEqualTo(200);
			assertThat(reused.body()).doesNotContain("password");
			assertThat(token).isNotBlank();
			assertThat(recovered.statusCode()).isEqualTo(200);
		}
	}

	@Test
	void boundsSessionsCreatedByRejectedUnsafeRequests() throws Exception {
		// Arrange
		try (var raw = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/categories"))
					.POST(HttpRequest.BodyPublishers.ofString("{}"));
			for (int index = 0; index < 3; index++) {
				raw.send(request.build(), HttpResponse.BodyHandlers.ofString());
			}

			// Act
			var response = raw.send(request.build(), HttpResponse.BodyHandlers.ofString());

			// Assert
			assertThat(response.statusCode()).isEqualTo(429);
			assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
		}
	}

	@Test
	void refusesExcessConcurrentLoginBeforeItCanWaitForDatabaseLocks() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = new ApiTestClient(port); var executor = Executors.newSingleThreadExecutor();
				var connection = jdbc.getDataSource().getConnection()) {
			String token = client.csrfToken();
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("select id from users where email=? for update")) {
				statement.setString(1, ADMIN_EMAIL);
				statement.executeQuery().close();
			}
			var pending = executor.submit(() -> client.login(ADMIN_EMAIL, ADMIN_PASSWORD, token));
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%users%'", Long.class) == 0
					&& System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%users%'", Long.class)).isPositive();

			// Act
			var busy = client.login("unknown@example.test", ADMIN_PASSWORD, token);
			connection.rollback();
			var completed = pending.get(15, TimeUnit.SECONDS);
			Thread.sleep(Duration.ofMillis(2100));
			var next = client.login(ADMIN_EMAIL, ADMIN_PASSWORD, client.csrfToken());

			// Assert
			assertThat(busy.statusCode()).isEqualTo(503);
			assertThat(completed.statusCode()).isEqualTo(204);
			assertThat(next.statusCode()).isEqualTo(204);
		}
	}
}
