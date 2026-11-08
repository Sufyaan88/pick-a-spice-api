package com.pickaspice.api.auth;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import com.pickaspice.api.config.AuthLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class AuthTrafficFilter extends OncePerRequestFilter {
	private final WindowLimit loginLimit;
	private final WindowLimit sessionLimit;
	private final Semaphore logins;
	private final Object sessionCreation = new Object();
	private final ObjectMapper json;
	private final long retrySeconds;

	public AuthTrafficFilter(AuthLimits limits, ObjectMapper json) {
		loginLimit = new WindowLimit(limits.loginAttemptsPerWindow(), limits);
		sessionLimit = new WindowLimit(limits.newSessionsPerWindow(), limits);
		logins = new Semaphore(limits.concurrentLogins());
		this.json = json;
		retrySeconds = Math.max(1, limits.trafficWindow().toSeconds()
				+ (limits.trafficWindow().getNano() == 0 ? 0 : 1));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean acquired = false;
		try {
			if (request.getMethod().equals("POST") && request.getServletPath().equals("/api/auth/login")) {
				loginLimit.admit(request.getRemoteAddr());
				acquired = logins.tryAcquire();
				if (!acquired) {
					throw new AdmissionRejected(503);
				}
			}
			// Guard actual creation, including CSRF sessions created by rejected unsafe requests.
			chain.doFilter(new HttpServletRequestWrapper(request) {
				@Override
				public HttpSession getSession() {
					return getSession(true);
				}

				@Override
				public HttpSession getSession(boolean create) {
					synchronized (sessionCreation) {
						HttpSession existing = super.getSession(false);
						if (existing != null || !create) {
							return existing;
						}
						sessionLimit.admit(getRemoteAddr());
						try {
							return super.getSession(true);
						}
						catch (TooManyActiveSessionsException exception) {
							throw new AdmissionRejected(503);
						}
					}
				}
			}, response);
		}
		catch (AdmissionRejected rejection) {
			reject(response, rejection.status);
		}
		catch (ServletException exception) {
			// MVC wraps an exception if token resolution was the first place a session was needed.
			Throwable cause = exception.getCause();
			while (cause != null && !(cause instanceof AdmissionRejected)) {
				cause = cause.getCause();
			}
			if (cause instanceof AdmissionRejected rejection) {
				reject(response, rejection.status);
			}
			else {
				throw exception;
			}
		}
		finally {
			if (acquired) {
				logins.release();
			}
		}
	}

	private void reject(HttpServletResponse response, int status) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setHeader("Retry-After", Long.toString(retrySeconds));
		json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),
				status == 429 ? "Too many authentication requests. Try again later." : "Authentication capacity reached. Try again later."));
	}

	private static final class AdmissionRejected extends RuntimeException {
		private final int status;

		private AdmissionRejected(int status) {
			super("Authentication traffic rejected.", null, false, false);
			this.status = status;
		}
	}

	private static final class WindowLimit {
		private final Map<String, Window> sources = new HashMap<>();
		private final int allowance;
		private final int capacity;
		private final long windowNanos;

		private WindowLimit(int allowance, AuthLimits limits) {
			this.allowance = allowance;
			capacity = limits.maxTrackedSources();
			windowNanos = limits.trafficWindow().toNanos();
		}

		private synchronized void admit(String address) {
			long now = System.nanoTime();
			Window window = sources.get(address);
			if (window != null && now - window.started < windowNanos) {
				if (window.used >= allowance) {
					throw new AdmissionRejected(429);
				}
				window.used++;
				return;
			}
			if (window == null && sources.size() >= capacity) {
				sources.values().removeIf(value -> now - value.started >= windowNanos);
				if (sources.size() >= capacity) {
					throw new AdmissionRejected(503);
				}
			}
			sources.put(address, new Window(now));
		}
	}

	private static final class Window {
		private final long started;
		private int used = 1;

		private Window(long started) {
			this.started = started;
		}
	}
}
