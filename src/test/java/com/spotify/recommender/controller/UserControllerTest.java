package com.spotify.recommender.controller;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.dto.spotify.ArtistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.SpotifyApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link UserController} (flagged as untested in the 2026-07-09 code
 * review, finding H4): {@code @Min}/{@code @Max} validation on {@code limit} and
 * the {@code timeRange} default.
 */
@WebMvcTest(UserController.class)
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
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpotifyApiService spotifyApi;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    // ── GET /api/user/profile ───────────────────────────────────────────────

    @Test
    @WithMockUser
    void profile_returnsSpotifyProfile() throws Exception {
        SpotifyUserDto profile = new SpotifyUserDto();
        profile.setId("spotify123");
        profile.setDisplayName("Jaideep");
        when(spotifyApi.getProfile()).thenReturn(profile);

        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("spotify123"))
            .andExpect(jsonPath("$.display_name").value("Jaideep"));
    }

    @Test
    void profile_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().is3xxRedirection());
    }

    // ── GET /api/user/top-tracks ─────────────────────────────────────────────

    @Test
    @WithMockUser
    void topTracks_noParams_usesDefaults() throws Exception {
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenReturn(new SpotifyPage<TrackDto>());

        mockMvc.perform(get("/api/user/top-tracks"))
            .andExpect(status().isOk());

        verify(spotifyApi).getTopTracks("medium_term", 20);
    }

    @Test
    @WithMockUser
    void topTracks_explicitParams_arePassedThrough() throws Exception {
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenReturn(new SpotifyPage<TrackDto>());

        mockMvc.perform(get("/api/user/top-tracks")
                .param("timeRange", "short_term")
                .param("limit", "5"))
            .andExpect(status().isOk());

        verify(spotifyApi).getTopTracks(eq("short_term"), eq(5));
    }

    @Test
    @WithMockUser
    void topTracks_limitAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/user/top-tracks").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void topTracks_limitBelowMin_returns400() throws Exception {
        mockMvc.perform(get("/api/user/top-tracks").param("limit", "0"))
            .andExpect(status().isBadRequest());
    }

    // ── GET /api/user/top-artists ────────────────────────────────────────────

    @Test
    @WithMockUser
    void topArtists_noParams_usesDefaults() throws Exception {
        when(spotifyApi.getTopArtists(anyString(), anyInt())).thenReturn(new SpotifyPage<ArtistDto>());

        mockMvc.perform(get("/api/user/top-artists"))
            .andExpect(status().isOk());

        verify(spotifyApi).getTopArtists("medium_term", 20);
    }

    @Test
    @WithMockUser
    void topArtists_limitAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/user/top-artists").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }
}
