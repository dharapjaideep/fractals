package com.spotify.recommender.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the Spring Security CSRF token to be resolved on every response.
 *
 * <p>When {@link org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler} is
 * used, the token is stored as a deferred {@link java.util.function.Supplier} and is only
 * written to the response cookie when it is actually accessed. For GET requests (which do not
 * require CSRF validation), nothing in the filter chain touches the token, so
 * {@link org.springframework.security.web.csrf.CookieCsrfTokenRepository} never writes the
 * {@code XSRF-TOKEN} cookie. Without the cookie the frontend cannot read the token to attach
 * to subsequent state-mutating requests (POST logout, POST chat), causing those requests to
 * fail CSRF validation with 403.
 *
 * <p>This filter resolves the deferred token unconditionally so the cookie is present on every
 * response, including the initial page load and unauthenticated GET responses.
 *
 * <p>Registered in {@link SecurityConfig} after {@code BasicAuthenticationFilter} so the
 * security context is fully populated before resolution.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    /**
     * Resolves the deferred CSRF token, causing {@link org.springframework.security.web.csrf.CookieCsrfTokenRepository}
     * to write the {@code XSRF-TOKEN} cookie to the response.
     *
     * @param request     the HTTP request
     * @param response    the HTTP response
     * @param filterChain the remaining filter chain
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        // Accessing getToken() forces the deferred supplier to resolve,
        // which triggers CookieCsrfTokenRepository.saveToken() and writes the cookie.
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
