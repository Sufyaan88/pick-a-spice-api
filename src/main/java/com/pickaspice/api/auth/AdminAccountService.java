package com.pickaspice.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.pickaspice.api.error.BusinessException;
import com.pickaspice.api.error.ErrorCode;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountService {

	private final UserAccountRepository repository;
	private final Validator validator;
	private final Set<String> blockedPasswords;
	private final PasswordEncoder passwordEncoder;

	public AdminAccountService(UserAccountRepository repository, Validator validator, PasswordEncoder passwordEncoder,
			@Value("classpath:security/blocked-passwords.txt") Resource blocklist) throws IOException {
		this.repository = repository;
		this.validator = validator;
		this.passwordEncoder = passwordEncoder;
		blockedPasswords = blocklist.getContentAsString(StandardCharsets.UTF_8).lines()
				.filter(line -> !line.isBlank()).map(line -> line.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	}

	@Transactional
	public void create(String email, CharSequence password) {
		String normalizedEmail = normalizeEmail(email);
		validatePassword(password);
		UserAccount account = new UserAccount(normalizedEmail, passwordEncoder.encode(password), UserAccount.Role.ADMIN);
		try {
			repository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "users_email_unique".equals(violation.getConstraintName())) {
					throw new BusinessException(ErrorCode.ADMIN_EMAIL_EXISTS, exception);
				}
			}
			throw exception;
		}
	}

	@Transactional
	public void resetPassword(String email, CharSequence password) {
		String normalizedEmail = normalizeEmail(email);
		validatePassword(password);
		UserAccount account = getLockedUser(normalizedEmail);
		account.resetPassword(passwordEncoder.encode(password));
		repository.clearLoginFailures(account.getId());
	}

	@Transactional
	public void setActive(String email, boolean active) {
		UserAccount account = getLockedUser(normalizeEmail(email));
		account.changeActive(active);
		if (active) {
			repository.clearLoginFailures(account.getId());
		}
	}

	@Transactional(readOnly = true)
	public Page<UserAccount.Summary> listUsers(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new BusinessException(ErrorCode.USER_PAGE_INVALID);
		}
		PageRequest request = PageRequest.of(page, size, Sort.by("id").ascending());
		if (request.getOffset() > Integer.MAX_VALUE) {
			throw new BusinessException(ErrorCode.PAGE_OFFSET_TOO_LARGE);
		}
		return repository.findAll(request).map(account -> new UserAccount.Summary(account.getId(), account.getEmail(),
				account.getRole().name(), account.isActive(), account.getLockedUntil()));
	}

	private UserAccount getLockedUser(String normalizedEmail) {
		return repository.findByEmail(normalizedEmail)
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private String normalizeEmail(String email) {
		String normalized = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
		if (!validator.validateValue(UserAccount.class, "email", normalized).isEmpty()) {
			throw new BusinessException(ErrorCode.ADMIN_EMAIL_INVALID);
		}
		return normalized;
	}

	private void validatePassword(CharSequence password) {
		if (password == null) {
			throw new BusinessException(ErrorCode.ADMIN_PASSWORD_INVALID);
		}
		int length = Character.codePointCount(password, 0, password.length());
		if (length < 15 || length > 128 || password.codePoints().allMatch(Character::isWhitespace)
				|| blockedPasswords.contains(password.toString().toLowerCase(Locale.ROOT))) {
			throw new BusinessException(ErrorCode.ADMIN_PASSWORD_INVALID);
		}
	}

}
