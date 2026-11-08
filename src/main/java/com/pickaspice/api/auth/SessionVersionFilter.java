package com.pickaspice.api.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class SessionVersionFilter extends OncePerRequestFilter {

	private final LoginService service;
	private final HttpSessionSecurityContextRepository contexts;
	private final AuthenticationEntryPoint unauthorized;
	private final Duration maximumSessionAge;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof UserAccount.Identity identity
				&& (identity.authenticatedAt() == null
						|| !Instant.now().isBefore(identity.authenticatedAt().plus(maximumSessionAge))
						|| !service.isCurrentSession(identity))) {
			var session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
			contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
			unauthorized.commence(request, response, new BadCredentialsException("Authentication required."));
			return;
		}
		chain.doFilter(request, response);
	}
}
