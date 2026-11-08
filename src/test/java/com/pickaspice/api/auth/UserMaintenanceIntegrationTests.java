package com.pickaspice.api.auth;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import com.pickaspice.api.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseConfiguration.class)
class UserMaintenanceIntegrationTests {

	private static final String NEW_PASSWORD = "meadow amber distant ocean";
	private static final String OTHER_EMAIL = "other@example.test";

	@Autowired
	private AdminAccountService accounts;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PasswordEncoder passwords;
	@Autowired
	private ConfigurableApplicationContext context;
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
	void resetChangesPasswordAndRevokesEverySessionWithoutAffectingOtherUsers() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		accounts.create(OTHER_EMAIL, ADMIN_PASSWORD);
		Map<String, Object> before = user(ADMIN_EMAIL);
		Map<String, Object> otherBefore = user(OTHER_EMAIL);
		try (var first = signedIn(ADMIN_EMAIL); var second = signedIn(ADMIN_EMAIL); var other = signedIn(OTHER_EMAIL)) {
			String oldToken = second.csrfToken();

			// Act
			accounts.resetPassword(" OWNER@EXAMPLE.TEST ", NEW_PASSWORD);
			HttpResponse<String> firstMe = first.request("GET", "/api/auth/me", "", null);
			HttpResponse<String> write = second.request("POST", "/api/admin/categories", "{\"name\":\"Must not be saved\"}", oldToken);
			HttpResponse<String> secondMe = second.request("GET", "/api/auth/me", "", null);
			HttpResponse<String> otherMe = other.request("GET", "/api/auth/me", "", null);
			HttpResponse<String> oldLogin = first.login(ADMIN_EMAIL, ADMIN_PASSWORD, first.csrfToken());
			HttpResponse<String> newLogin = first.login(ADMIN_EMAIL, NEW_PASSWORD, first.csrfToken());

			// Assert
			assertThat(firstMe.statusCode()).isEqualTo(401);
			assertThat(write.statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("select count(*) from categories", Long.class)).isZero();
			assertThat(secondMe.statusCode()).isEqualTo(401);
			assertThat(otherMe.statusCode()).isEqualTo(200);
			assertThat(oldLogin.statusCode()).isEqualTo(401);
			assertThat(newLogin.statusCode()).isEqualTo(204);
			Map<String, Object> after = user(ADMIN_EMAIL);
			assertThat(after).containsEntry("id", before.get("id")).containsEntry("email", ADMIN_EMAIL)
					.containsEntry("active", true).containsEntry("role", "ADMIN");
			assertThat(after.get("password_hash")).isNotEqualTo(before.get("password_hash"));
			assertThat(passwords.matches(NEW_PASSWORD, (String) after.get("password_hash"))).isTrue();
			assertThat(user(OTHER_EMAIL)).isEqualTo(otherBefore);
		}
	}

	@Test
	void resetClearsLockoutAndHistoryInOneTransaction() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = new ApiTestClient(port)) {
			String token = client.csrfToken();
			for (int index = 0; index < 5; index++) {
				client.login(ADMIN_EMAIL, "incorrect password", token);
			}
			jdbc.update("insert into login_failures (user_id, attempted_at) select id,current_timestamp from users where email=?", ADMIN_EMAIL);

			// Act
			accounts.resetPassword(ADMIN_EMAIL, NEW_PASSWORD);
			Map<String, Object> after = user(ADMIN_EMAIL);
			long failures = jdbc.queryForObject("select count(*) from login_failures", Long.class);
			HttpResponse<String> login = client.login(ADMIN_EMAIL, NEW_PASSWORD, client.csrfToken());

			// Assert
			assertThat(after.get("locked_until")).isNull();
			assertThat(failures).isZero();
			assertThat(login.statusCode()).isEqualTo(204);
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"short", "passwordpassword", "PASSWORDPASSWORD", "               "})
	void rejectedResetLeavesUserAndExistingSessionUnchanged(String password) throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		Map<String, Object> before = user(ADMIN_EMAIL);
		try (var client = signedIn(ADMIN_EMAIL)) {

			// Act
			Throwable failure = catchThrowable(() -> accounts.resetPassword(ADMIN_EMAIL, password));
			HttpResponse<String> me = client.request("GET", "/api/auth/me", "", null);

			// Assert
			assertThat(failure).isInstanceOf(BusinessException.class);
			assertThat(user(ADMIN_EMAIL)).isEqualTo(before);
			assertThat(me.statusCode()).isEqualTo(200);
		}
	}

	@Test
	void resetDoesNotReactivateDisabledAccount() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		jdbc.update("update users set active=false where email=?", ADMIN_EMAIL);

		// Act
		accounts.resetPassword(ADMIN_EMAIL, NEW_PASSWORD);

		// Assert
		assertThat(user(ADMIN_EMAIL)).containsEntry("active", false);
		assertThat(passwords.matches(NEW_PASSWORD, (String) user(ADMIN_EMAIL).get("password_hash"))).isTrue();
	}

	@Test
	void resetMissingUserDoesNotCreateAnAccount() {
		// Arrange
		String email = "missing@example.test";

		// Act
		Throwable failure = catchThrowable(() -> accounts.resetPassword(email, NEW_PASSWORD));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class).hasMessage("User not found.");
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
	}

	@Test
	void resetCommandChangesPasswordAndClearsInputBuffers() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		char[] password = NEW_PASSWORD.toCharArray();
		char[] confirmation = NEW_PASSWORD.toCharArray();
		var command = new UserMaintenanceCommand(accounts, context);

		// Act
		command.reset(ADMIN_EMAIL, password, confirmation);

		// Assert
		assertThat(passwords.matches(NEW_PASSWORD, (String) user(ADMIN_EMAIL).get("password_hash"))).isTrue();
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
	}

	@Test
	void resetCommandRejectsConfirmationMismatchAndClearsBuffers() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		Map<String, Object> before = user(ADMIN_EMAIL);
		char[] password = NEW_PASSWORD.toCharArray();
		char[] confirmation = ADMIN_PASSWORD.toCharArray();
		var command = new UserMaintenanceCommand(accounts, context);

		// Act
		Throwable failure = catchThrowable(() -> command.reset(ADMIN_EMAIL, password, confirmation));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class);
		assertThat(user(ADMIN_EMAIL)).isEqualTo(before);
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
	}

	@ParameterizedTest
	@ValueSource(strings = {"reset-password", "disable", "reactivate"})
	void userChangesRefuseRedirectedInput(String operation) {
		// Arrange
		assertThat(System.console()).isNull();
		var command = new UserMaintenanceCommand(accounts, context);
		var arguments = new DefaultApplicationArguments("--operation=" + operation);

		// Act
		Throwable failure = catchThrowable(() -> command.run(arguments));

		// Assert
		assertThat(failure).isInstanceOf(IllegalStateException.class)
				.hasMessage("User changes require an interactive terminal. Passwords cannot be supplied through arguments or redirected input.");
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
	}

	@Test
	void disablingRevokesSessionsAndBlocksLoginWithoutChangingOtherUsers() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		accounts.create(OTHER_EMAIL, ADMIN_PASSWORD);
		Map<String, Object> before = user(ADMIN_EMAIL);
		Map<String, Object> otherBefore = user(OTHER_EMAIL);
		try (var first = signedIn(ADMIN_EMAIL); var second = signedIn(ADMIN_EMAIL); var other = signedIn(OTHER_EMAIL)) {

			// Act
			accounts.setActive(" OWNER@EXAMPLE.TEST ", false);
			var firstMe = first.request("GET", "/api/auth/me", "", null);
			var secondMe = second.request("GET", "/api/auth/me", "", null);
			var otherMe = other.request("GET", "/api/auth/me", "", null);
			var login = first.login(ADMIN_EMAIL, ADMIN_PASSWORD, first.csrfToken());

			// Assert
			assertThat(firstMe.statusCode()).isEqualTo(401);
			assertThat(secondMe.statusCode()).isEqualTo(401);
			assertThat(otherMe.statusCode()).isEqualTo(200);
			assertThat(login.statusCode()).isEqualTo(401);
			assertThat(user(ADMIN_EMAIL)).containsEntry("active", false)
					.containsEntry("password_hash", before.get("password_hash")).containsEntry("id", before.get("id"));
			assertThat(user(OTHER_EMAIL)).isEqualTo(otherBefore);
		}
	}

	@Test
	void reactivationClearsLockoutAndRequiresFreshLoginWithoutChangingPassword() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		Map<String, Object> before = user(ADMIN_EMAIL);
		try (var oldSession = signedIn(ADMIN_EMAIL); var attacker = new ApiTestClient(port)) {
			String token = attacker.csrfToken();
			for (int index = 0; index < 5; index++) {
				attacker.login(ADMIN_EMAIL, "incorrect password", token);
			}
			jdbc.update("insert into login_failures (user_id, attempted_at) select id,current_timestamp from users where email=?", ADMIN_EMAIL);

			// Act
			accounts.setActive(ADMIN_EMAIL, false);
			accounts.setActive(ADMIN_EMAIL, true);
			Map<String, Object> after = user(ADMIN_EMAIL);
			long failures = jdbc.queryForObject("select count(*) from login_failures", Long.class);
			var oldMe = oldSession.request("GET", "/api/auth/me", "", null);
			var freshLogin = attacker.login(ADMIN_EMAIL, ADMIN_PASSWORD, attacker.csrfToken());

			// Assert
			assertThat(oldMe.statusCode()).isEqualTo(401);
			assertThat(freshLogin.statusCode()).isEqualTo(204);
			assertThat(after).containsEntry("active", true).containsEntry("locked_until", null)
					.containsEntry("password_hash", before.get("password_hash")).containsEntry("id", before.get("id"));
			assertThat(failures).isZero();
		}
	}

	@Test
	void repeatedStatusChangesAreIdempotentAndDoNotRevokeHealthySessions() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);

		// Act
		accounts.setActive(ADMIN_EMAIL, false);
		Map<String, Object> firstDisable = user(ADMIN_EMAIL);
		accounts.setActive(ADMIN_EMAIL, false);
		Map<String, Object> secondDisable = user(ADMIN_EMAIL);
		accounts.setActive(ADMIN_EMAIL, true);
		try (var current = signedIn(ADMIN_EMAIL)) {
			Map<String, Object> beforeRepeat = user(ADMIN_EMAIL);
			accounts.setActive(ADMIN_EMAIL, true);
			var me = current.request("GET", "/api/auth/me", "", null);

			// Assert
			assertThat(secondDisable).isEqualTo(firstDisable);
			assertThat(user(ADMIN_EMAIL)).isEqualTo(beforeRepeat);
			assertThat(me.statusCode()).isEqualTo(200);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void changingMissingUserStatusDoesNotCreateAnAccount(boolean active) {
		// Arrange
		String email = "missing@example.test";

		// Act
		Throwable failure = catchThrowable(() -> accounts.setActive(email, active));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class).hasMessage("User not found.");
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
	}

	@Test
	void cancelledResetClearsTheAvailablePasswordBuffer() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var before = user(ADMIN_EMAIL);
		char[] password = NEW_PASSWORD.toCharArray();
		var command = new UserMaintenanceCommand(accounts, context);

		// Act
		Throwable failure = catchThrowable(() -> command.reset(ADMIN_EMAIL, password, null));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class);
		assertThat(password).containsOnly('\0');
		assertThat(user(ADMIN_EMAIL)).isEqualTo(before);
	}

	@Test
	void invalidResetClearsBothPasswordBuffers() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var before = user(ADMIN_EMAIL);
		char[] password = "short".toCharArray();
		char[] confirmation = "short".toCharArray();
		var command = new UserMaintenanceCommand(accounts, context);

		// Act
		Throwable failure = catchThrowable(() -> command.reset(ADMIN_EMAIL, password, confirmation));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
		assertThat(user(ADMIN_EMAIL)).isEqualTo(before);
	}

	@Test
	void concurrentLoginWithOldPasswordCannotSurviveReset() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var start = new CountDownLatch(1);
		try (var client = new ApiTestClient(port); var executor = Executors.newFixedThreadPool(2)) {
			String token = client.csrfToken();
			var reset = executor.submit(() -> {
				if (!start.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting for reset.");
				}
				accounts.resetPassword(ADMIN_EMAIL, NEW_PASSWORD);
				return true;
			});
			var login = executor.submit(() -> {
				if (!start.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting for login.");
				}
				return client.login(ADMIN_EMAIL, ADMIN_PASSWORD, token);
			});

			// Act
			start.countDown();
			reset.get(30, TimeUnit.SECONDS);
			var loginResult = login.get(30, TimeUnit.SECONDS);
			var me = client.request("GET", "/api/auth/me", "", null);
			var fresh = client.login(ADMIN_EMAIL, NEW_PASSWORD, client.csrfToken());

			// Assert
			assertThat(loginResult.statusCode()).isIn(204, 401);
			assertThat(me.statusCode()).isEqualTo(401);
			assertThat(fresh.statusCode()).isEqualTo(204);
		}
		finally {
			start.countDown();
		}
	}

	@Test
	void deletedUserCannotKeepUsingAnExistingSession() throws Exception {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		try (var client = signedIn(ADMIN_EMAIL)) {
			jdbc.update("delete from users where email=?", ADMIN_EMAIL);

			// Act
			var response = client.request("GET", "/api/auth/me", "", null);

			// Assert
			assertThat(response.statusCode()).isEqualTo(401);
		}
	}

	private Map<String, Object> user(String email) {
		return jdbc.queryForMap("select * from users where email=?", email);
	}

	private ApiTestClient signedIn(String email) throws Exception {
		var client = new ApiTestClient(port);
		try {
			HttpResponse<String> response = client.login(email, ADMIN_PASSWORD, client.csrfToken());
			assertThat(response.statusCode()).isEqualTo(204);
			return client;
		}
		catch (Exception | AssertionError failure) {
			client.close();
			throw failure;
		}
	}
}
