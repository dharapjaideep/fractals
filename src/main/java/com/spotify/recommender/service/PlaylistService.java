package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import org.springframework.stereotype.Service;

/**
 * Thin delegator that exposes Spotify playlist reads to controllers.
 * All I/O is forwarded to {@link SpotifyApiService}; no business logic lives here.
 */
@Service
public class PlaylistService {

    private final SpotifyApiService spotifyApi;

    public PlaylistService(SpotifyApiService spotifyApi) {
        this.spotifyApi = spotifyApi;
    }

    /**
     * Fetches a paginated page of the authenticated user's playlists.
     *
     * @param offset zero-based index of the first playlist to return
     * @param limit  maximum number of playlists to return (Spotify max: 50)
     * @return a page of {@link PlaylistDto} items
     */
    public SpotifyPage<PlaylistDto> getPlaylists(int offset, int limit) {
        return spotifyApi.getPlaylists(offset, limit);
    }

    /**
     * Fetches a paginated page of tracks in a playlist.
     *
     * @param playlistId Spotify playlist ID
     * @param offset     zero-based index of the first track to return
     * @param limit      maximum number of tracks to return (Spotify max: 100)
     * @return a page of {@link PlaylistTrackDto} items
     */
    public SpotifyPage<PlaylistTrackDto> getPlaylistTracks(String playlistId, int offset, int limit) {
        return spotifyApi.getPlaylistTracks(playlistId, offset, limit);
    }
}
