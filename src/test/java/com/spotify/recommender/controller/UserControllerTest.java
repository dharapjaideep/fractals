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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link UserController}: delegation, the {@code timeRange} default, and the
 * fail-safe validation of {@code timeRange} and {@code limit}.
 */
@WebMvcTest(UserController.class)
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
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpotifyApiService spotifyApi;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @Test
    void profile_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser
    void profile_delegatesToService() throws Exception {
        when(spotifyApi.getProfile()).thenReturn(new SpotifyUserDto());

        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().isOk());

        verify(spotifyApi).getProfile();
    }

    @Test
    @WithMockUser
    void topTracks_usesMediumTermDefault_whenTimeRangeOmitted() throws Exception {
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenReturn(new SpotifyPage<TrackDto>());

        mockMvc.perform(get("/api/user/top-tracks"))
            .andExpect(status().isOk());

        // Default time range and limit are applied when the caller omits them.
        verify(spotifyApi).getTopTracks("medium_term", 20);
    }

    @Test
    @WithMockUser
    void topTracks_validTimeRange_delegates() throws Exception {
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenReturn(new SpotifyPage<TrackDto>());

        mockMvc.perform(get("/api/user/top-tracks").param("timeRange", "short_term").param("limit", "5"))
            .andExpect(status().isOk());

        verify(spotifyApi).getTopTracks("short_term", 5);
    }

    @Test
    @WithMockUser
    void topTracks_invalidTimeRange_returns400() throws Exception {
        // Fail safe: an unsupported time_range is rejected locally rather than forwarded to Spotify.
        mockMvc.perform(get("/api/user/top-tracks").param("timeRange", "all_time"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void topTracks_limitExceedsMax_returns400() throws Exception {
        mockMvc.perform(get("/api/user/top-tracks").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void topArtists_invalidTimeRange_returns400() throws Exception {
        mockMvc.perform(get("/api/user/top-artists").param("timeRange", "bogus"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void topArtists_validTimeRange_delegates() throws Exception {
        when(spotifyApi.getTopArtists(anyString(), anyInt())).thenReturn(new SpotifyPage<ArtistDto>());

        mockMvc.perform(get("/api/user/top-artists").param("timeRange", "long_term").param("limit", "15"))
            .andExpect(status().isOk());

        verify(spotifyApi).getTopArtists("long_term", 15);
    }
}
