package com.spotify.recommender.controller;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link AuthController} (flagged as untested in the 2026-07-09 code
 * review, finding H4): the {@code /api/auth/me} 401-vs-authenticated branch
 * and {@code UserProfileDto} mapping.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
    // Minimal fake registration so SecurityConfig's oauth2Login() can initialize
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
    @WithMockUser
    void me_authenticationIsNotOAuth2Token_returns401() throws Exception {
        // @WithMockUser produces a UsernamePasswordAuthenticationToken, which passes
        // SecurityConfig's anyRequest().authenticated() check but is not an
        // OAuth2AuthenticationToken — exercises the controller's own 401 branch.
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void me_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void me_oauth2Authenticated_returnsMappedProfile() throws Exception {
        AppUser user = new AppUser();
        user.setSpotifyId("spotify123");
        user.setDisplayName("Jaideep");
        user.setEmail("jaideep@example.com");
        when(userService.findBySpotifyId("spotify123")).thenReturn(user);

        mockMvc.perform(get("/api/auth/me").with(authentication(oauth2Token("spotify123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.spotifyId").value("spotify123"))
            .andExpect(jsonPath("$.displayName").value("Jaideep"))
            .andExpect(jsonPath("$.email").value("jaideep@example.com"));
    }

    private static OAuth2AuthenticationToken oauth2Token(String spotifyId) {
        OAuth2User principal = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("id", spotifyId),
            "id");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "spotify");
    }
}
