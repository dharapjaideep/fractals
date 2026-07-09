package com.spotify.recommender.controller;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.service.PlaylistService;
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
 * Slice tests for {@link PlaylistController}: delegation to {@link PlaylistService} and the
 * pagination constraints on {@code offset} and {@code limit}.
 */
@WebMvcTest(PlaylistController.class)
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
class PlaylistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlaylistService playlistService;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @Test
    void playlists_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/playlists"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser
    void playlists_usesDefaults_whenParamsOmitted() throws Exception {
        when(playlistService.getPlaylists(anyInt(), anyInt())).thenReturn(new SpotifyPage<PlaylistDto>());

        mockMvc.perform(get("/api/playlists"))
            .andExpect(status().isOk());

        verify(playlistService).getPlaylists(0, 20);
    }

    @Test
    @WithMockUser
    void playlists_delegatesWithSuppliedPagination() throws Exception {
        when(playlistService.getPlaylists(anyInt(), anyInt())).thenReturn(new SpotifyPage<PlaylistDto>());

        mockMvc.perform(get("/api/playlists").param("offset", "40").param("limit", "10"))
            .andExpect(status().isOk());

        verify(playlistService).getPlaylists(40, 10);
    }

    @Test
    @WithMockUser
    void playlists_negativeOffset_returns400() throws Exception {
        mockMvc.perform(get("/api/playlists").param("offset", "-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void playlists_limitExceedsMax_returns400() throws Exception {
        mockMvc.perform(get("/api/playlists").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void playlistTracks_delegatesWithPathAndPagination() throws Exception {
        when(playlistService.getPlaylistTracks(anyString(), anyInt(), anyInt()))
            .thenReturn(new SpotifyPage<PlaylistTrackDto>());

        mockMvc.perform(get("/api/playlists/pl-1/tracks").param("offset", "0").param("limit", "50"))
            .andExpect(status().isOk());

        verify(playlistService).getPlaylistTracks("pl-1", 0, 50);
    }

    @Test
    @WithMockUser
    void playlistTracks_limitExceedsMax_returns400() throws Exception {
        mockMvc.perform(get("/api/playlists/pl-1/tracks").param("limit", "101"))
            .andExpect(status().isBadRequest());
    }
}
