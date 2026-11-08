package com.pickaspice.api.auth;

import java.io.PrintWriter;
import java.io.StringWriter;

import com.pickaspice.api.TestDatabaseConfiguration;
import com.pickaspice.api.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestDatabaseConfiguration.class)
class UserListingIntegrationTests {

	@Autowired
	private AdminAccountService accounts;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ConfigurableApplicationContext context;

	@BeforeEach
	@AfterEach
	void clean() {
		jdbc.update("delete from users");
	}

	@Test
	void listsActiveInactiveAndLockedUsersInIdOrderWithoutChangingData() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		accounts.create("inactive@example.test", ADMIN_PASSWORD);
		accounts.setActive("inactive@example.test", false);
		accounts.create("locked@example.test", ADMIN_PASSWORD);
		jdbc.update("update users set locked_until=current_timestamp + interval '15 minutes' where email='locked@example.test'");
		var before = jdbc.queryForList("select * from users order by id");

		// Act
		var first = accounts.listUsers(0, 1);
		var rest = accounts.listUsers(1, 1);
		var all = accounts.listUsers(0, 100);

		// Assert
		assertThat(first.getTotalElements()).isEqualTo(3);
		assertThat(first.getContent()).extracting(UserAccount.Summary::email).containsExactly(ADMIN_EMAIL);
		assertThat(rest.getContent()).extracting(UserAccount.Summary::email).containsExactly("inactive@example.test");
		assertThat(rest.getContent().getFirst().active()).isFalse();
		assertThat(all.getContent()).extracting(UserAccount.Summary::email)
				.containsExactly(ADMIN_EMAIL, "inactive@example.test", "locked@example.test");
		assertThat(all.getContent().getLast().lockedUntil()).isNotNull();
		assertThat(jdbc.queryForList("select * from users order by id")).isEqualTo(before);
	}

	@Test
	void listsEmptyDatabaseAndPagesBeyondAvailableUsers() {
		// Arrange
		int size = 20;

		// Act
		var empty = accounts.listUsers(0, size);
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var beyond = accounts.listUsers(Integer.MAX_VALUE, 1);

		// Assert
		assertThat(empty.getContent()).isEmpty();
		assertThat(empty.getTotalElements()).isZero();
		assertThat(beyond.getContent()).isEmpty();
		assertThat(beyond.getTotalElements()).isEqualTo(1);
		assertThat(beyond.getNumber()).isEqualTo(Integer.MAX_VALUE);
	}

	@ParameterizedTest
	@CsvSource({"-1,20", "0,0", "0,-1", "0,101", "2147483647,100"})
	void rejectsInvalidPaginationWithoutChangingUsers(int page, int size) {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var before = jdbc.queryForList("select * from users");

		// Act
		Throwable failure = catchThrowable(() -> accounts.listUsers(page, size));

		// Assert
		assertThat(failure).isInstanceOf(BusinessException.class);
		assertThat(jdbc.queryForList("select * from users")).isEqualTo(before);
	}

	@Test
	void terminalListPrintsSafeFieldsAndPaginationOnly() {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		String hash = jdbc.queryForObject("select password_hash from users", String.class);
		var command = new UserMaintenanceCommand(accounts, context);
		var text = new StringWriter();
		var output = new PrintWriter(text);

		// Act
		command.list(0, 20, output);

		// Assert
		assertThat(text.toString()).contains(ADMIN_EMAIL, "ADMIN", "true", "Page 0", "size 20", "total 1")
				.doesNotContain(ADMIN_PASSWORD, hash, "password_hash", "session_version");
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "1.5", "2147483648", ""})
	void terminalRejectsNonIntegerPaginationSafely(String page) {
		// Arrange
		var command = new UserMaintenanceCommand(accounts, context);
		var arguments = new DefaultApplicationArguments("--operation=list", "--page=" + page);

		// Act
		Throwable failure = catchThrowable(() -> command.run(arguments));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("Page and size must be integers.");
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "unknown", "duplicate-operation", "password", "page-with-change", "duplicate-page"})
	void rejectedCommandArgumentsDoNotChangeUsers(String scenario) {
		// Arrange
		accounts.create(ADMIN_EMAIL, ADMIN_PASSWORD);
		var before = jdbc.queryForList("select * from users");
		String[] options = switch (scenario) {
			case "missing" -> new String[] {};
			case "unknown" -> new String[] {"--operation=delete"};
			case "duplicate-operation" -> new String[] {"--operation=list", "--operation=disable"};
			case "password" -> new String[] {"--operation=reset-password", "--password=do-not-log-me"};
			case "page-with-change" -> new String[] {"--operation=disable", "--page=0"};
			default -> new String[] {"--operation=list", "--page=0", "--page=1"};
		};
		var arguments = new DefaultApplicationArguments(options);
		var command = new UserMaintenanceCommand(accounts, context);

		// Act
		Throwable failure = catchThrowable(() -> command.run(arguments));

		// Assert
		assertThat(failure).isInstanceOf(IllegalArgumentException.class);
		assertThat(failure.getMessage()).doesNotContain("do-not-log-me");
		assertThat(jdbc.queryForList("select * from users")).isEqualTo(before);
	}
}
