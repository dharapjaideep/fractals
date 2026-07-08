package com.spotify.recommender.config;

import com.spotify.recommender.controller.PlaceholderController;
import com.spotify.recommender.service.PlaceholderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests that {@link SecurityConfig} permits unauthenticated GET access to the app's
 * static informational pages ({@code /logged-out.html}, {@code /invite-only.html}),
 * which are served outside any controller and so aren't covered by controller-level
 * {@code @WebMvcTest} assertions directly. Scoped to {@link PlaceholderController} —
 * an arbitrary, lightweight existing controller — purely so the slice has a bean to
 * anchor on; static resource handling is part of the shared MVC auto-configuration
 * this slice loads regardless of which controller is named.
 *
 * <p>{@code @WebMvcTest} does not scan plain {@code @Configuration} classes like
 * {@link SecurityConfig} by default (other controller tests in this repo pass their
 * unauthenticated-access assertions against Spring Boot's default auto-configured
 * security filter chain instead — coincidentally similar enough not to have surfaced
 * this). {@code @Import} forces the real bean in so these assertions exercise the
 * actual {@code permitAll} rules rather than the default fallback.
 */
@WebMvcTest(PlaceholderController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.client.registration.spotify.client-id=test-id",
    "spring.security.oauth2.client.registration.spotify.client-secret=test-secret",
    "spring.security.oauth2.client.registration.spotify.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.spotify.redirect-uri=http://localhost/cb",
    "spring.security.oauth2.client.registration.spotify.scope=openid",
    "spring.security.oauth2.client.provider.spotify.authorization-uri=https://accounts.spotify.com/authorize",
    "spring.security.oauth2.client.provider.spotify.token-uri=https://accounts.spotify.com/api/token",
    "spring.security.oauth2.client.provider.spotify.user-info-uri=https://api.spotify.com/v1/me",
    "spring.security.oauth2.client.provider.spotify.user-name-attribute=id"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlaceholderService placeholderService;

    // SecurityConfig constructor-injects these; provide mocks so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @MockBean
    private SpotifyLogoutHandler spotifyLogoutHandler;

    @MockBean
    private InviteOnlyAuthenticationFailureHandler inviteOnlyFailureHandler;

    @Test
    void inviteOnlyPage_unauthenticated_isPermitted() throws Exception {
        mockMvc.perform(get("/invite-only.html"))
            .andExpect(status().isOk());
    }

    @Test
    void loggedOutPage_unauthenticated_isPermitted() throws Exception {
        mockMvc.perform(get("/logged-out.html"))
            .andExpect(status().isOk());
    }
}
