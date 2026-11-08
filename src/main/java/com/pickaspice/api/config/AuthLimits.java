package com.pickaspice.api.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthLimits(int loginAttemptsPerWindow, int newSessionsPerWindow, Duration trafficWindow,
		int concurrentLogins, int maxTrackedSources, int maxSessions, Duration maximumSessionAge) {
	public AuthLimits {
		if (loginAttemptsPerWindow < 1 || newSessionsPerWindow < 1 || concurrentLogins < 1
				|| maxTrackedSources < 1 || maxSessions < 1 || trafficWindow == null
				|| trafficWindow.isNegative() || trafficWindow.isZero() || maximumSessionAge == null
				|| maximumSessionAge.isNegative() || maximumSessionAge.isZero()) {
			throw new IllegalArgumentException("Authentication limits and durations must be positive.");
		}
		trafficWindow.toNanos();
	}
}
