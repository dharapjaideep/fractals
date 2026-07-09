package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlaylistService}, verifying that it is a pure delegator to
 * {@link SpotifyApiService} and passes its arguments through unchanged.
 */
@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @Mock
    private SpotifyApiService spotifyApi;

    @InjectMocks
    private PlaylistService playlistService;

    @Test
    void getPlaylists_delegatesWithSameArguments() {
        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        when(spotifyApi.getPlaylists(10, 25)).thenReturn(page);

        SpotifyPage<PlaylistDto> result = playlistService.getPlaylists(10, 25);

        assertThat(result).isSameAs(page);
        verify(spotifyApi).getPlaylists(10, 25);
    }

    @Test
    void getPlaylistTracks_delegatesWithSameArguments() {
        SpotifyPage<PlaylistTrackDto> page = new SpotifyPage<>();
        when(spotifyApi.getPlaylistTracks("pl-1", 5, 40)).thenReturn(page);

        SpotifyPage<PlaylistTrackDto> result = playlistService.getPlaylistTracks("pl-1", 5, 40);

        assertThat(result).isSameAs(page);
        verify(spotifyApi).getPlaylistTracks("pl-1", 5, 40);
    }
}
