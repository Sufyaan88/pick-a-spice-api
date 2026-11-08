package com.pickaspice.api.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class JsonLoginFilter extends AbstractAuthenticationProcessingFilter {

	private final ObjectMapper json;

	public JsonLoginFilter(AuthenticationManager manager, ObjectMapper json) {
		super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"), manager);
		this.json = json;
	}

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String contentType = request.getContentType();
		if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase(MediaType.APPLICATION_JSON_VALUE)) {
			throw invalidInput();
		}
		byte[] body = request.getInputStream().readNBytes(4097);
		if (body.length > 4096) {
			throw invalidInput();
		}
		LoginRequest credentials;
		try {
			JsonNode node = json.readTree(body);
			if (node == null || !node.isObject() || !node.path("email").isTextual() || !node.path("password").isTextual()) {
				throw invalidInput();
			}
			credentials = new LoginRequest(node.path("email").asText(), node.path("password").asText());
		}
		catch (JacksonException exception) {
			// Parser exception messages can contain credential fragments. Do not log or attach them to authentication errors.
			throw invalidInput();
		}
		if (credentials.email().isBlank() || credentials.email().strip().length() > 254
				|| credentials.password().isBlank() || credentials.password().codePointCount(0, credentials.password().length()) > 128) {
			throw invalidInput();
		}
		return getAuthenticationManager().authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(credentials.email(), credentials.password()));
	}

	private AuthenticationServiceException invalidInput() {
		return new AuthenticationServiceException("Invalid login request.");
	}

	private record LoginRequest(String email, String password) {
		@Override
		public String toString() {
			return "LoginRequest[credentials omitted]";
		}
	}
}
