package com.pickaspice.api.auth;

import java.util.List;
import java.util.Map;

import com.pickaspice.api.TestDatabaseConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static com.pickaspice.api.auth.AdminFixtures.ADMIN_EMAIL;
import static com.pickaspice.api.auth.AdminFixtures.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestDatabaseConfiguration.class)
class UserAccountMigrationIntegrationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void cleanUpgradeSchema() {
		jdbc.execute("drop schema if exists users_upgrade_test cascade");
	}

	@Test
	void migrationPreservesExistingAccountsAndMarksThemAsAdmins() {
		// Arrange
		Flyway.configure().dataSource(jdbc.getDataSource()).schemas("users_upgrade_test")
				.defaultSchema("users_upgrade_test").target("4").load().migrate();
		String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(ADMIN_PASSWORD);
		jdbc.update("insert into users_upgrade_test.admin_accounts (email, password_hash, active) values (?, ?, ?)",
				ADMIN_EMAIL, hash, true);
		jdbc.update("insert into users_upgrade_test.admin_accounts (email, password_hash, active) values (?, ?, ?)",
				"inactive@example.test", hash, false);
		List<Map<String, Object>> before = jdbc.queryForList("select * from users_upgrade_test.admin_accounts order by id");

		// Act
		Flyway.configure().dataSource(jdbc.getDataSource()).schemas("users_upgrade_test")
				.defaultSchema("users_upgrade_test").load().migrate();

		// Assert
		List<Map<String, Object>> after = jdbc.queryForList("select * from users_upgrade_test.users order by id");
		assertThat(after).hasSize(2);
		assertThat(after.get(0)).containsAllEntriesOf(before.get(0)).containsEntry("role", "ADMIN").containsEntry("session_version", 0L);
		assertThat(after.get(1)).containsAllEntriesOf(before.get(1)).containsEntry("role", "ADMIN").containsEntry("session_version", 0L);
		assertThat(jdbc.queryForObject("select to_regclass('users_upgrade_test.admin_accounts') is null", Boolean.class)).isTrue();
	}

	@Test
	void migratedUsersContinueGeneratingIdsBeyondExistingAccounts() {
		// Arrange
		Flyway.configure().dataSource(jdbc.getDataSource()).schemas("users_upgrade_test")
				.defaultSchema("users_upgrade_test").target("4").load().migrate();
		String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(ADMIN_PASSWORD);
		Long previousId = jdbc.queryForObject("""
				insert into users_upgrade_test.admin_accounts (email, password_hash, active)
				values (?, ?, true) returning id
				""", Long.class, ADMIN_EMAIL, hash);
		Flyway.configure().dataSource(jdbc.getDataSource()).schemas("users_upgrade_test")
				.defaultSchema("users_upgrade_test").load().migrate();

		// Act
		Long nextId = jdbc.queryForObject("""
				insert into users_upgrade_test.users (email, password_hash, active, role)
				values (?, ?, true, 'ADMIN') returning id
				""", Long.class, "next@example.test", hash);

		// Assert
		assertThat(nextId).isGreaterThan(previousId);
		assertThat(jdbc.queryForObject("select count(*) from users_upgrade_test.users", Long.class)).isEqualTo(2);
	}

}
