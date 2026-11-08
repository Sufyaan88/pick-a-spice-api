package com.pickaspice.api.config;

import java.io.IOException;
import java.util.List;

import com.pickaspice.api.auth.AuthTrafficFilter;
import com.pickaspice.api.auth.JsonLoginFilter;
import com.pickaspice.api.auth.LoginService;
import com.pickaspice.api.auth.SessionVersionFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.session.StandardManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthLimits.class)
public class SecurityConfiguration {

	@Bean
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> sessionCapacity(AuthLimits limits) {
		return factory -> factory.addContextCustomizers(context -> {
			var manager = new StandardManager();
			manager.setPathname(null);
			manager.setMaxActiveSessions(limits.maxSessions());
			context.setManager(manager);
		});
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		// OWASP's Argon2id minimum: 19 MiB, two iterations, one lane, with per-password random salts.
		return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
	}

	@Bean
	AuthenticationManager authenticationManager(LoginService service) {
		return authentication -> {
			var identity = service.authenticate(authentication.getName(), (String) authentication.getCredentials())
					.orElseThrow(() -> new BadCredentialsException("Unable to sign in with those credentials."));
			return UsernamePasswordAuthenticationToken.authenticated(identity, null,
					AuthorityUtils.createAuthorityList("ROLE_" + identity.role()));
		};
	}

	@Bean
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager manager, ObjectMapper json, LoginService service,
			AuthLimits limits) throws Exception {
		var contexts = new HttpSessionSecurityContextRepository();
		var tokens = new HttpSessionCsrfTokenRepository();
		AuthenticationEntryPoint unauthorized = (request, response, exception) -> problem(json, response, 401, "Authentication required.");
		var login = new JsonLoginFilter(manager, json);
		login.setSecurityContextRepository(contexts);
		login.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
				new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens))));
		login.setAuthenticationSuccessHandler((request, response, authentication) -> response.setStatus(204));
		login.setAuthenticationFailureHandler((request, response, exception) -> {
			contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
			int status = exception instanceof AuthenticationServiceException ? 400 : 401;
			problem(json, response, status, status == 400 ? "Invalid login request." : "Unable to sign in with those credentials.");
		});
		http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.securityContext(context -> context.securityContextRepository(contexts))
				.csrf(csrf -> csrf.csrfTokenRepository(tokens))
				.authorizeHttpRequests(access -> access
						.requestMatchers("/api/auth/csrf", "/api/auth/login").permitAll()
						.requestMatchers("/api/admin/**", "/api/auth/me").hasRole("ADMIN")
						.anyRequest().denyAll())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler((request, response, exception) -> problem(json, response, 403, "Access denied.")))
				.logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID")
						.logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)).permitAll())
				.addFilterAt(login, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(new AuthTrafficFilter(limits, json), HeaderWriterFilter.class)
				.addFilterAfter(new SessionVersionFilter(service, contexts, unauthorized, limits.maximumSessionAge()), AuthTrafficFilter.class);
		return http.build();
	}

	private void problem(ObjectMapper json, HttpServletResponse response, int status, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail));
	}
}
