package com.pickaspice.api.auth;

import java.time.Duration;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "app.auth.maximum-session-age=3s")
@Import(TestDatabaseConfiguration.class)
class AbsoluteSessionIntegrationTests {
	@Autowired
	private AdminAccountService accounts;
	@Autowired
	private JdbcTemplate jdbc;
	@Value("${local.server.port}")
	private int port;

	@BeforeEach
	@AfterEach
	void clean() {
		jdbc.update("delete from products");
		jdbc.update("delete from categories");
		jdbc.update("delete from users");
	}

	@Test
	void expiredSessionCannotMakeItsFirstRequestAWrite() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = new ApiTestClient(port)) {
			client.signIn();
			String token = client.csrfToken();
			Thread.sleep(Duration.ofMillis(3200));

			// Act
			var response = client.request("POST", "/api/admin/categories", "{\"name\":\"Expired write\"}", token);

			// Assert
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("select count(*) from categories", Long.class)).isZero();
		}
	}

	@Test
	void absoluteExpiryStartsAtLoginRejectsWritesDespiteActivityAndAllowsFreshLogin() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = new ApiTestClient(port)) {
			String anonymousToken = client.csrfToken();
			Thread.sleep(Duration.ofMillis(3200));
			var login = client.login(ADMIN_EMAIL, ADMIN_PASSWORD, anonymousToken);
			String token = client.csrfToken();
			var initially = client.request("GET", "/api/auth/me", "", null);

			// Act
			for (int index = 0; index < 7; index++) {
				Thread.sleep(500);
				client.request("GET", "/api/auth/csrf", "", null);
			}
			var write = client.request("POST", "/api/admin/categories", "{\"name\":\"Expired write\"}", token);
			var expired = client.request("GET", "/api/auth/me", "", null);
			var fresh = client.login(ADMIN_EMAIL, ADMIN_PASSWORD, client.csrfToken());
			var freshMe = client.request("GET", "/api/auth/me", "", null);

			// Assert
			assertThat(login.statusCode()).isEqualTo(204);
			assertThat(initially.statusCode()).isEqualTo(200);
			assertThat(write.statusCode()).isIn(401, 403);
			assertThat(expired.statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("select count(*) from categories", Long.class)).isZero();
			assertThat(fresh.statusCode()).isEqualTo(204);
			assertThat(freshMe.statusCode()).isEqualTo(200);
		}
	}
}
