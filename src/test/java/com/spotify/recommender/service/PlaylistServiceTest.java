package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PlaylistService}, a thin delegator to {@link SpotifyApiService}.
 * Verifies argument pass-through and return-value delegation
 * (flagged as untested in the 2026-07-09 code review, finding H4).
 */
@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @Mock
    private SpotifyApiService spotifyApi;

    private PlaylistService playlistService;

    @BeforeEach
    void setUp() {
        playlistService = new PlaylistService(spotifyApi);
    }

    @Test
    void getPlaylists_delegatesToSpotifyApiWithSameArguments() {
        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        when(spotifyApi.getPlaylists(10, 25)).thenReturn(page);

        SpotifyPage<PlaylistDto> result = playlistService.getPlaylists(10, 25);

        assertThat(result).isSameAs(page);
        verify(spotifyApi).getPlaylists(10, 25);
    }

    @Test
    void getPlaylistTracks_delegatesToSpotifyApiWithSameArguments() {
        SpotifyPage<PlaylistTrackDto> page = new SpotifyPage<>();
        when(spotifyApi.getPlaylistTracks("playlist1", 0, 50)).thenReturn(page);

        SpotifyPage<PlaylistTrackDto> result = playlistService.getPlaylistTracks("playlist1", 0, 50);

        assertThat(result).isSameAs(page);
        verify(spotifyApi).getPlaylistTracks("playlist1", 0, 50);
    }
}
