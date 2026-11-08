package com.pickaspice.api.auth;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import(TestDatabaseConfiguration.class)
class SecureSessionCookieIntegrationTests {

	@Value("${local.server.port}")
	private int port;

	@Test
	void deploymentSettingMarksSessionCookieSecureAndHttpOnly() throws Exception {
		// Arrange
		try (var client = new ApiTestClient(port)) {

			// Act
			var response = client.request("GET", "/api/auth/csrf", "", null);

			// Assert
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().allValues("Set-Cookie").toString())
					.contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
		}
	}
}
