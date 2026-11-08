package com.pickaspice.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

	private final UserAccountRepository repository;
	private final PasswordEncoder encoder;
	private final String dummyHash;

	public LoginService(UserAccountRepository repository, PasswordEncoder encoder) {
		this.repository = repository;
		this.encoder = encoder;
		dummyHash = encoder.encode("Dummy authentication work, never an account password");
	}

	@Transactional(readOnly = true)
	public boolean isCurrentSession(UserAccount.Identity identity) {
		return repository.findById(identity.id())
				.filter(account -> account.isActive() && account.getSessionVersion() == identity.sessionVersion()
						&& account.getRole().name().equals(identity.role()))
				.isPresent();
	}

	// Return a failed result instead of throwing, so failure history commits before HTTP authentication rejects it.
	@Transactional
	public Optional<UserAccount.Identity> authenticate(String email, CharSequence password) {
		Optional<UserAccount> found = repository.findByEmail(email.strip().toLowerCase(Locale.ROOT));
		boolean matches = encoder.matches(password, found.map(UserAccount::getPasswordHash).orElse(dummyHash));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		UserAccount account = found.get();
		Instant now = Instant.now();
		if (!account.isActive() || account.getRole() != UserAccount.Role.ADMIN
				|| (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now))) {
			return Optional.empty();
		}
		account.lockUntil(null);
		repository.deleteExpiredLoginFailures(account.getId(), now.minus(Duration.ofMinutes(15)));
		if (matches) {
			repository.clearLoginFailures(account.getId());
			return Optional.of(account.identity());
		}
		repository.recordLoginFailure(account.getId(), now);
		long failures = repository.countLoginFailures(account.getId());
		if (failures >= 5) {
			account.lockUntil(now.plus(Duration.ofMinutes(15)));
			repository.clearLoginFailures(account.getId());
		}
		return Optional.empty();
	}
}
