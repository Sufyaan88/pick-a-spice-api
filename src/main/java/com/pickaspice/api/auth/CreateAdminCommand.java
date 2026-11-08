package com.pickaspice.api.auth;

import java.io.Console;
import java.nio.CharBuffer;
import java.util.Arrays;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("create-admin")
@RequiredArgsConstructor
public class CreateAdminCommand implements ApplicationRunner {

	private final AdminAccountService service;
	private final ConfigurableApplicationContext context;

	@Override
	public void run(ApplicationArguments arguments) {
		if (context.getEnvironment().matchesProfiles("manage-users")) {
			throw new IllegalArgumentException("Use only one account command profile at a time.");
		}
		Console console = System.console();
		if (console == null) {
			throw new IllegalStateException("Admin setup requires an interactive terminal. Passwords cannot be supplied through arguments or redirected input.");
		}
		String email = console.readLine("Admin email: ");
		char[] password = null;
		char[] confirmation = null;
		try {
			password = console.readPassword("Password: ");
			confirmation = console.readPassword("Confirm password: ");
			create(email, password, confirmation);
			console.printf("Admin account created.%n");
		}
		finally {
			clear(password);
			clear(confirmation);
		}
		context.close();
	}

	void create(String email, char[] password, char[] confirmation) {
		try {
			if (email == null || password == null || confirmation == null) {
				throw new IllegalArgumentException("Admin setup cancelled. No account was created.");
			}
			if (!Arrays.equals(password, confirmation)) {
				throw new IllegalArgumentException("Passwords do not match. No account was created.");
			}
			service.create(email, CharBuffer.wrap(password));
		}
		finally {
			clear(password);
			clear(confirmation);
		}
	}

	private void clear(char[] value) {
		if (value != null) {
			Arrays.fill(value, '\0');
		}
	}

}
