package com.pickaspice.api.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getToken(), token.getHeaderName());
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal UserAccount.Identity user) {
		return new UserResponse(user.id(), user.email(), user.role());
	}

	public record UserResponse(Long id, String email, String role) {
	}

	public record CsrfResponse(String token, String headerName) {
		@Override
		public String toString() {
			return "CsrfResponse[token omitted]";
		}
	}
}
