package com.pickaspice.api.auth;

import java.util.Optional;
import java.time.Instant;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<UserAccount> findByEmail(String email);

	@Modifying
	@Query(value = "delete from login_failures where user_id=:userId", nativeQuery = true)
	void clearLoginFailures(Long userId);

	@Modifying
	@Query(value = "delete from login_failures where user_id=:userId and attempted_at <= :cutoff", nativeQuery = true)
	void deleteExpiredLoginFailures(Long userId, Instant cutoff);

	@Modifying
	@Query(value = "insert into login_failures (user_id, attempted_at) values (:userId, :attemptedAt)", nativeQuery = true)
	void recordLoginFailure(Long userId, Instant attemptedAt);

	@Query(value = "select count(*) from login_failures where user_id=:userId", nativeQuery = true)
	long countLoginFailures(Long userId);
}
