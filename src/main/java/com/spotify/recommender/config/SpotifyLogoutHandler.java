package com.spotify.recommender.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Removes the Spotify OAuth2 authorized client record from the database on logout.
 *
 * <p>Without this, the token row in {@code oauth2_authorized_client} persists after the
 * HTTP session is invalidated. Spring Security would then reuse the stored token on the
 * next successful OAuth2 login rather than treating the user as fully logged out.
 *
 * <p>Registered as an additional {@link LogoutHandler} in {@link SecurityConfig} alongside
 * Spring's default {@code SecurityContextLogoutHandler} (which handles session invalidation
 * and security context clearing). Failures are caught and logged rather than thrown — a
 * failed DB cleanup must not prevent the logout response from completing (fail-safe principle).
 */
@Component
public class SpotifyLogoutHandler implements LogoutHandler {

    private static final Logger log = LoggerFactory.getLogger(SpotifyLogoutHandler.class);

    private final OAuth2AuthorizedClientService authorizedClientService;

    public SpotifyLogoutHandler(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * Removes the Spotify authorized client entry for the authenticated user.
     *
     * @param request        the HTTP request (unused — no request state needed for DB cleanup)
     * @param response       the HTTP response (unused)
     * @param authentication the current authentication; if null, the cleanup is skipped
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        try {
            authorizedClientService.removeAuthorizedClient("spotify", authentication.getName());
            log.debug("Removed Spotify authorized client for user '{}'", authentication.getName());
        } catch (RuntimeException e) {
            // Fail-safe: a DB error must not prevent the session from being invalidated.
            log.warn("Failed to remove Spotify authorized client for user '{}' on logout",
                authentication.getName(), e);
        }
    }
}
