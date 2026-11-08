package com.pickaspice.api.auth;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

	enum Role {
		ADMIN
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotBlank
	@Email
	@Size(max = 254)
	@Column(nullable = false, length = 254)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false)
	private boolean active;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	// Explicit authentication version: login failure updates must not revoke existing sessions.
	@Column(name = "session_version", nullable = false)
	private long sessionVersion;

	UserAccount(String email, String passwordHash, Role role) {
		this.email = email;
		this.passwordHash = passwordHash;
		active = true;
		this.role = role;
	}

	void lockUntil(Instant until) {
		lockedUntil = until;
	}

	void resetPassword(String hash) {
		passwordHash = hash;
		lockedUntil = null;
		sessionVersion = Math.incrementExact(sessionVersion);
	}

	void changeActive(boolean active) {
		if (this.active != active) {
			this.active = active;
			sessionVersion = Math.incrementExact(sessionVersion);
		}
		if (active) {
			lockedUntil = null;
		}
	}

	Identity identity() {
		return new Identity(id, email, role.name(), sessionVersion, Instant.now());
	}

	public record Summary(Long id, String email, String role, boolean active, Instant lockedUntil) {
	}

	public record Identity(Long id, String email, String role, long sessionVersion, Instant authenticatedAt) implements Principal, Serializable {
		@Override
		public String getName() {
			return email;
		}
	}

}
