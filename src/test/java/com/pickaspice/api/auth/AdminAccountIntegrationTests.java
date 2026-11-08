package com.pickaspice.api.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.pickaspice.api.TestDatabaseConfiguration;
import com.pickaspice.api.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// Account creation is a terminal operation, so its application boundary is the service, not HTTP.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestDatabaseConfiguration.class)
class AdminAccountIntegrationTests {

	@Autowired
	private AdminAccountService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ConfigurableApplicationContext context;

	@BeforeEach
	@AfterEach
	void cleanAccounts() {
		jdbc.update("delete from users");
	}

	@Test
	void createsActiveAccountWithNormalizedEmailAndVerifiableArgon2idHash() {
		// Arrange
		String email = "  OWNER@Example.Test  ";

		// Act
		service.create(email, ADMIN_PASSWORD);

		// Assert
		Map<String, Object> saved = jdbc.queryForMap("select * from users");
		assertThat((Long) saved.get("id")).isPositive();
		assertThat(saved).containsEntry("email", ADMIN_EMAIL).containsEntry("active", true).containsEntry("role", "ADMIN");
		String hash = (String) saved.get("password_hash");
		assertThat(hash).startsWith("$argon2id$").doesNotContain(ADMIN_PASSWORD);
		var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		assertThat(encoder.matches(ADMIN_PASSWORD, hash)).isTrue();
		assertThat(encoder.matches("a different long password", hash)).isFalse();
	}

	@Test
	void saltsEachAccountPasswordIndependently() {
		// Arrange
		service.create(ADMIN_EMAIL, ADMIN_PASSWORD);

		// Act
		service.create("second@example.test", ADMIN_PASSWORD);

		// Assert
		List<String> hashes = jdbc.queryForList("select password_hash from users", String.class);
		assertThat(hashes).hasSize(2).doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "owner", "owner@", "@example.test", "owner @example.test"})
	void rejectsInvalidEmailWithoutSavingAnything(String email) {
		// Arrange
		String password = ADMIN_PASSWORD;

		// Act
		Throwable failure = catchThrowable(() -> service.create(email, password));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class)
				.hasMessage("Enter a valid email address of at most 254 characters.");
		assertThat(accountCount()).isZero();
	}

	@Test
	void rejectsEmailLongerThan254Characters() {
		// Arrange
		String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60) + ".test";

		// Act
		Throwable failure = catchThrowable(() -> service.create(email, ADMIN_PASSWORD));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(accountCount()).isZero();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"short", "12345678901234", "               "})
	void rejectsMissingShortOrWhitespaceOnlyPasswords(String password) {
		// Arrange
		String email = ADMIN_EMAIL;

		// Act
		Throwable failure = catchThrowable(() -> service.create(email, password));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class)
				.hasMessage("Use 15 to 128 characters and avoid blocked passwords.");
		assertThat(accountCount()).isZero();
	}

	@Test
	void rejectsPasswordLongerThan128Characters() {
		// Arrange
		String password = "x".repeat(129);

		// Act
		Throwable failure = catchThrowable(() -> service.create(ADMIN_EMAIL, password));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(accountCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"passwordpassword", "password123456789", "password12345678", "123456789012345",
			"1234567890123456", "12345678901234567890", "qwertyuiopasdfgh", "abcdefghijklmnop",
			"letmeinletmeinletmein", "welcome123456789", "admin123456789012", "administrator123",
			"changeme123456789", "iloveyouiloveyou", "pickaspice123456", "pick-a-spice12345", "PASSWORDPASSWORD"})
	void rejectsEveryAgreedBlockedPasswordIgnoringCase(String password) {
		// Arrange
		String email = ADMIN_EMAIL;

		// Act
		Throwable failure = catchThrowable(() -> service.create(email, password));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(accountCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"cedar river sky", "cedar river skyy", "  cedar river orbit lantern  ", "树🌶️ river lantern meadow"})
	void preservesAcceptedPasswordsIncludingSpacesAndUnicode(String password) {
		// Arrange
		String email = ADMIN_EMAIL;

		// Act
		service.create(email, password);

		// Assert
		String hash = jdbc.queryForObject("select password_hash from users", String.class);
		assertThat(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(password, hash)).isTrue();
	}

	@Test
	void accepts128CharacterPassword() {
		// Arrange
		String password = "cedar river " + "x".repeat(116);

		// Act
		service.create(ADMIN_EMAIL, password);

		// Assert
		String hash = jdbc.queryForObject("select password_hash from users", String.class);
		assertThat(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(password, hash)).isTrue();
	}

	@Test
	void rejectsDuplicateEmailWithoutChangingExistingAccount() {
		// Arrange
		service.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var before = jdbc.queryForList("select * from users");

		// Act
		Throwable failure = catchThrowable(() -> service.create(" OWNER@EXAMPLE.TEST ", "another long passphrase"));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class)
				.hasMessage("An admin account with this email already exists.").hasCauseInstanceOf(RuntimeException.class);
		assertThat(jdbc.queryForList("select * from users")).isEqualTo(before);
	}

	@Test
	void concurrentCreationAllowsOnlyOneAccountForAnEmail() throws Exception {
		// Arrange
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		Callable<Throwable> attempt = () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for concurrent creation.");
			}
			return catchThrowable(() -> service.create(ADMIN_EMAIL, ADMIN_PASSWORD));
		};
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(attempt);
			var second = executor.submit(attempt);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

			// Act
			start.countDown();
			Throwable firstFailure = first.get(20, TimeUnit.SECONDS);
			Throwable secondFailure = second.get(20, TimeUnit.SECONDS);

			// Assert
			assertThat(Arrays.asList(firstFailure, secondFailure)).filteredOn(value -> value == null).hasSize(1);
			assertThat(Arrays.asList(firstFailure, secondFailure)).filteredOn(value -> value != null)
					.singleElement().satisfies(failure -> assertThat(failure).isInstanceOf(BusinessException.class)
							.hasMessage("An admin account with this email already exists."));
			assertThat(accountCount()).isEqualTo(1);
		}
		finally {
			start.countDown();
		}
	}

	@Test
	void ordinaryStartupDoesNotCreateAccountsOrEnableSetupCommand() {
		// Arrange
		long before = accountCount();

		// Act
		boolean commandEnabled = context.containsBean("createAdminCommand");

		// Assert
		assertThat(commandEnabled).isFalse();
		assertThat(before).isZero();
		assertThat(accountCount()).isZero();
	}

	@Test
	void commandRefusesRedirectedInputWithoutCreatingAnAccount() {
		// Arrange
		assertThat(System.console()).as("Surefire has no interactive console").isNull();
		var command = new CreateAdminCommand(service, context);

		// Act
		Throwable failure = catchThrowable(() -> command.run(new DefaultApplicationArguments()));

		// Assert
		assertThat(failure).isInstanceOf(IllegalStateException.class)
				.hasMessage("Admin setup requires an interactive terminal. Passwords cannot be supplied through arguments or redirected input.");
		assertThat(accountCount()).isZero();
	}

	@Test
	void commandCreatesAccountAndClearsBothPasswordBuffers() {
		// Arrange
		char[] password = ADMIN_PASSWORD.toCharArray();
		char[] confirmation = ADMIN_PASSWORD.toCharArray();
		var command = new CreateAdminCommand(service, context);

		// Act
		command.create(ADMIN_EMAIL, password, confirmation);

		// Assert
		assertThat(accountCount()).isEqualTo(1);
		String hash = jdbc.queryForObject("select password_hash from users", String.class);
		assertThat(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(ADMIN_PASSWORD, hash)).isTrue();
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
	}

	@Test
	void commandCreatesAdminInSharedUsersTable() {
		// Arrange
		var command = new CreateAdminCommand(service, context);
		char[] password = ADMIN_PASSWORD.toCharArray();
		char[] confirmation = ADMIN_PASSWORD.toCharArray();

		// Act
		command.create(ADMIN_EMAIL, password, confirmation);

		// Assert
		assertThat(jdbc.queryForMap("select email, active, role from users"))
				.containsEntry("email", ADMIN_EMAIL).containsEntry("active", true).containsEntry("role", "ADMIN");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"OWNER"})
	void databaseRejectsMissingOrUnknownRolesWithoutSavingUser(String role) {
		// Arrange
		String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(ADMIN_PASSWORD);

		// Act
		Throwable failure = catchThrowable(() -> jdbc.update("""
				insert into users (email, password_hash, active, role) values (?, ?, true, ?)
				""", ADMIN_EMAIL, hash, role));

		// Assert
		assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(accountCount()).isZero();
	}

	@Test
	void databaseDoesNotDefaultNewUsersToAdmin() {
		// Arrange
		String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(ADMIN_PASSWORD);

		// Act
		Throwable failure = catchThrowable(() -> jdbc.update("""
				insert into users (email, password_hash, active) values (?, ?, true)
				""", ADMIN_EMAIL, hash));

		// Assert
		assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(accountCount()).isZero();
	}

	@Test
	void commandRejectsMismatchingConfirmationAndClearsBothBuffers() {
		// Arrange
		char[] password = ADMIN_PASSWORD.toCharArray();
		char[] confirmation = "a different long passphrase".toCharArray();
		var command = new CreateAdminCommand(service, context);

		// Act
		Throwable failure = catchThrowable(() -> command.create(ADMIN_EMAIL, password, confirmation));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Passwords do not match. No account was created.");
		assertThat(accountCount()).isZero();
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
	}

	@Test
	void commandClearsBuffersWhenAccountValidationFails() {
		// Arrange
		char[] password = ADMIN_PASSWORD.toCharArray();
		char[] confirmation = ADMIN_PASSWORD.toCharArray();
		var command = new CreateAdminCommand(service, context);

		// Act
		Throwable failure = catchThrowable(() -> command.create("invalid-email", password, confirmation));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(accountCount()).isZero();
		assertThat(password).containsOnly('\0');
		assertThat(confirmation).containsOnly('\0');
	}

	@Test
	void commandCancelsOnEndOfInputAndClearsAvailableBuffer() {
		// Arrange
		char[] password = ADMIN_PASSWORD.toCharArray();
		var command = new CreateAdminCommand(service, context);

		// Act
		Throwable failure = catchThrowable(() -> command.create(ADMIN_EMAIL, password, null));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Admin setup cancelled. No account was created.");
		assertThat(accountCount()).isZero();
		assertThat(password).containsOnly('\0');
	}

	private long accountCount() {
		return jdbc.queryForObject("select count(*) from users", Long.class);
	}

}
