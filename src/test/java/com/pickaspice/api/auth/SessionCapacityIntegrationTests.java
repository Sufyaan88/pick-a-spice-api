package com.pickaspice.api.auth;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "app.auth.max-sessions=2")
@Import(TestDatabaseConfiguration.class)
class SessionCapacityIntegrationTests {
	@Value("${local.server.port}")
	private int port;

	@Test
	void refusesNewSessionsAtCapacityButExistingSessionsCanLogoutToFreeSpace() throws Exception {
		// Arrange
		try (var first = new ApiTestClient(port); var second = new ApiTestClient(port); var third = new ApiTestClient(port)) {
			String token = first.csrfToken();
			second.csrfToken();

			// Act
			var rejected = third.request("GET", "/api/auth/csrf", "", null);
			var existing = second.request("GET", "/api/auth/csrf", "", null);
			var logout = first.request("POST", "/api/auth/logout", "", token);
			var recovered = third.request("GET", "/api/auth/csrf", "", null);

			// Assert
			assertThat(rejected.statusCode()).isEqualTo(503);
			assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
			assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
			assertThat(existing.statusCode()).isEqualTo(200);
			assertThat(logout.statusCode()).isEqualTo(204);
			assertThat(recovered.statusCode()).isEqualTo(200);
		}
	}
}
