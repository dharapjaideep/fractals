package com.spotify.recommender.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security configuration for the Spotify Recommender application.
 *
 * <p>Configures CSRF (double-submit cookie via {@link CookieCsrfTokenRepository}),
 * OAuth2 login, session management, logout, and authorization rules.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OAuth2SuccessHandler successHandler;
    private final SpotifyLogoutHandler spotifyLogoutHandler;
    private final InviteOnlyAuthenticationFailureHandler inviteOnlyFailureHandler;

    public SecurityConfig(OAuth2SuccessHandler successHandler,
                          SpotifyLogoutHandler spotifyLogoutHandler,
                          InviteOnlyAuthenticationFailureHandler inviteOnlyFailureHandler) {
        this.successHandler = successHandler;
        this.spotifyLogoutHandler = spotifyLogoutHandler;
        this.inviteOnlyFailureHandler = inviteOnlyFailureHandler;
    }

    /**
     * Builds the primary security filter chain.
     *
     * <p>Static public resources (root path, HTML pages, CSS/JS) use
     * {@link AntPathRequestMatcher} rather than the default {@link
     * org.springframework.security.web.access.intercept.AuthorizationFilter}
     * MvcRequestMatcher, because MvcRequestMatcher for {@code "/"} delegates to
     * {@code WelcomePageHandlerMapping}, which requires an {@code Accept: text/html}
     * header at match time — unavailable in the security filter chain — causing
     * {@code "/"} to fall through to {@code anyRequest().authenticated()} and
     * trigger an OAuth redirect for unauthenticated visitors.
     *
     * <p>{@link HttpStatusEntryPoint} returns 401 for unauthenticated {@code /api/**}
     * requests instead of redirecting to OAuth, so {@code init()} in the frontend can
     * detect the unauthenticated state and show the login button rather than silently
     * following the redirect into the Spotify OAuth flow.
     *
     * @param http the {@link HttpSecurity} to configure
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if Spring Security configuration fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // CSRF exempted for /mcp/** — MCP clients (including Claude Code)
                // cannot perform the cookie double-submit CSRF handshake because
                // they are not browsers and never load the app's HTML to read the
                // CSRF token. Session authentication still applies on this path.
                // IMPORTANT: MCP tools must remain read-only. Any write tool added
                // to SpotifyMcpTools.java requires re-evaluating this exemption —
                // without CSRF protection, a cross-site request could trigger write
                // operations using a victim's session cookie.
                .ignoringRequestMatchers("/h2-console/**", "/mcp/**"))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                // AntPathRequestMatcher bypasses the MvcRequestMatcher / WelcomePageHandlerMapping
                // delegation that prevents "/" from being matched reliably (see Javadoc above).
                // Restricted to GET — no POST/PUT/DELETE to these paths without authentication.
                .requestMatchers(
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/index.html"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/logged-out.html"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/invite-only.html"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/favicon.ico"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/*.css"),
                    AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/*.js")
                ).permitAll()
                .requestMatchers("/oauth2/**", "/login/**", "/h2-console/**").permitAll()
                // Functionally already covered by anyRequest().authenticated() below;
                // stated explicitly per CLAUDE.md's least-privilege principle so the
                // MCP endpoint's required permission is visible at this call site.
                .requestMatchers("/mcp/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .successHandler(successHandler)
                .failureHandler(inviteOnlyFailureHandler))
            // Return 401 (not an OAuth redirect) for unauthenticated /api/** requests.
            // The frontend's init() detects 401 and shows the login button, so the user
            // deliberately clicks "Sign in with Spotify" to start the OAuth flow.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")))
            .logout(logout -> logout
                // Logout URL matches the frontend's fetch('/logout', ...) call.
                .logoutUrl("/logout")
                // SpotifyLogoutHandler removes the token from oauth2_authorized_client
                // before SecurityContextLogoutHandler invalidates the session.
                .addLogoutHandler(spotifyLogoutHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                // Return 200 — the frontend navigates to /logged-out.html itself after
                // the fetch completes, so the user sees a clear confirmation page rather
                // than being silently redirected to "/" which may auto-trigger Spotify login.
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(200)));

        // Allow H2 console iframes in dev
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        // Re-register CsrfCookieFilter so the XSRF-TOKEN cookie is written on every response.
        // With CsrfTokenRequestAttributeHandler (deferred), safe GETs never touch the token, so
        // CookieCsrfTokenRepository only writes the cookie when something accesses it server-side.
        // Without this filter, a fresh session (login → immediate logout before any chat) has no
        // cookie, the POST /logout 403s, the catch swallows it, and the user lands on /logged-out.html
        // while the server session and Spotify token remain valid — logout fails open.
        http.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }
}