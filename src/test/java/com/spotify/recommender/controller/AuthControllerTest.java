package com.spotify.recommender.controller;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link AuthController}'s {@code GET /api/auth/me} endpoint: the
 * unauthenticated redirect, the non-OAuth2 principal 401 branch, and the authenticated
 * DTO-mapping happy path.
 */
@WebMvcTest(AuthController.class)
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
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @Test
    void me_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser
    void me_nonOAuth2Principal_returns401() throws Exception {
        // @WithMockUser yields a UsernamePasswordAuthenticationToken, not an
        // OAuth2AuthenticationToken, exercising the controller's fail-safe 401 branch.
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void me_authenticatedViaOAuth2_returnsProfileDto() throws Exception {
        AppUser user = new AppUser();
        user.setSpotifyId("spotify-123");
        user.setDisplayName("Jay");
        user.setEmail("jay@example.com");
        when(userService.findBySpotifyId(anyString())).thenReturn(user);

        mockMvc.perform(get("/api/auth/me").with(oauth2Login()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.spotifyId").value("spotify-123"))
            .andExpect(jsonPath("$.displayName").value("Jay"))
            .andExpect(jsonPath("$.email").value("jay@example.com"));
    }
}
