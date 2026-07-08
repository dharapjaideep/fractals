package com.spotify.recommender.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Redirects to a branded invite-only page whenever OAuth2 login fails.
 *
 * <p>The most common cause is Spotify returning {@code error=access_denied} on the
 * callback because the user isn't on the app's allowlist (Spotify apps in Development
 * Mode restrict login to explicitly added users). Without this handler, Spring Security's
 * default {@code SimpleUrlAuthenticationFailureHandler} redirects to {@code /login?error},
 * which has no mapped page in this app and falls through to the Whitelabel error page.
 *
 * <p>Registered as the {@code failureHandler} in {@link SecurityConfig}'s {@code oauth2Login}
 * configuration. Any OAuth2 login failure lands here, not just allowlist denial.
 */
@Component
public class InviteOnlyAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(InviteOnlyAuthenticationFailureHandler.class);

    /**
     * Redirects the browser to the invite-only page.
     *
     * @param request   the HTTP request (unused — no request state needed for the redirect)
     * @param response  the HTTP response the redirect is written to
     * @param exception the authentication failure that triggered this handler
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        log.info("OAuth2 login failed, redirecting to invite-only page: {}", exception.getMessage());
        response.sendRedirect("/invite-only.html");
    }
}
