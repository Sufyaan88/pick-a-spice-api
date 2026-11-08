package com.pickaspice.api.auth;

import java.io.Console;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("manage-users")
@RequiredArgsConstructor
public class UserMaintenanceCommand implements ApplicationRunner {

	private final AdminAccountService accounts;
	private final ConfigurableApplicationContext context;

	@Override
	public void run(ApplicationArguments arguments) {
		if (context.getEnvironment().matchesProfiles("create-admin")) {
			throw new IllegalArgumentException("Use only one account command profile at a time.");
		}
		if (!arguments.getNonOptionArgs().isEmpty() || arguments.getOptionNames().stream().anyMatch(name ->
				!List.of("operation", "page", "size", "debug").contains(name)
						&& !name.startsWith("spring.") && !name.startsWith("logging."))) {
			throw new IllegalArgumentException("Unsupported command option.");
		}
		List<String> operations = arguments.getOptionValues("operation");
		if (operations == null || operations.size() != 1
				|| !List.of("reset-password", "disable", "reactivate", "list").contains(operations.getFirst())) {
			throw new IllegalArgumentException("Specify exactly one supported --operation.");
		}
		String operation = operations.getFirst();
		if (operation.equals("list")) {
			list(integerOption(arguments, "page", 0), integerOption(arguments, "size", 20), new PrintWriter(System.out, true));
			context.close();
			return;
		}
		if (arguments.containsOption("page") || arguments.containsOption("size")) {
			throw new IllegalArgumentException("Pagination options are only supported for listing users.");
		}
		Console console = System.console();
		if (console == null) {
			throw new IllegalStateException("User changes require an interactive terminal. Passwords cannot be supplied through arguments or redirected input.");
		}
		String email = console.readLine("User email: ");
		if (email == null) {
			throw new IllegalArgumentException("User maintenance cancelled. No changes were saved.");
		}
		if (operation.equals("reset-password")) {
			resetFromConsole(console, email);
		}
		else {
			accounts.setActive(email, operation.equals("reactivate"));
			console.printf(operation.equals("reactivate") ? "User reactivated. Fresh login required.%n"
					: "User disabled. Previous sessions have been revoked.%n");
		}
		context.close();
	}

	private void resetFromConsole(Console console, String email) {
		char[] password = null;
		char[] confirmation = null;
		try {
			password = console.readPassword("New password: ");
			confirmation = console.readPassword("Confirm password: ");
			reset(email, password, confirmation);
			console.printf("Password reset. Previous sessions have been revoked.%n");
		}
		finally {
			clear(password);
			clear(confirmation);
		}
	}

	void reset(String email, char[] password, char[] confirmation) {
		try {
			if (email == null || password == null || confirmation == null) {
				throw new IllegalArgumentException("User maintenance cancelled. No changes were saved.");
			}
			if (!Arrays.equals(password, confirmation)) {
				throw new IllegalArgumentException("Passwords do not match. No changes were saved.");
			}
			accounts.resetPassword(email, CharBuffer.wrap(password));
		}
		finally {
			clear(password);
			clear(confirmation);
		}
	}

	void list(int page, int size, PrintWriter output) {
		var users = accounts.listUsers(page, size);
		output.println("ID\tEmail\tRole\tActive\tLocked until (UTC)");
		for (var user : users.getContent()) {
			output.printf("%d\t%s\t%s\t%s\t%s%n", user.id(), user.email(), user.role(), user.active(),
					user.lockedUntil() == null ? "-" : user.lockedUntil());
		}
		output.printf("Page %d, size %d, total %d%n", users.getNumber(), users.getSize(), users.getTotalElements());
		output.flush();
	}

	private int integerOption(ApplicationArguments arguments, String name, int defaultValue) {
		List<String> values = arguments.getOptionValues(name);
		if (values == null) {
			return defaultValue;
		}
		if (values.size() != 1) {
			throw new IllegalArgumentException("Page and size must be integers.");
		}
		try {
			return Integer.parseInt(values.getFirst());
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Page and size must be integers.");
		}
	}

	private void clear(char[] value) {
		if (value != null) {
			Arrays.fill(value, '\0');
		}
	}
}
