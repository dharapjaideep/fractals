package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.spotify.ArtistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.SpotifyApiService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the authenticated user's Spotify profile and taste data
 * ({@code /api/user/**}). Thin delegator — all Spotify I/O goes through {@link SpotifyApiService}.
 */
@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {

    // Spotify only accepts these three time_range values; forwarding anything else yields an
    // opaque upstream 400. Constrain here so bad input fails safe with a local 400 instead.
    private static final String TIME_RANGE_PATTERN = "short_term|medium_term|long_term";

    private final SpotifyApiService spotifyApi;

    public UserController(SpotifyApiService spotifyApi) {
        this.spotifyApi = spotifyApi;
    }

    /**
     * Returns the authenticated user's Spotify profile.
     *
     * @return the user's {@link SpotifyUserDto} profile
     */
    @GetMapping("/profile")
    public SpotifyUserDto profile() {
        return spotifyApi.getProfile();
    }

    /**
     * Returns the authenticated user's top tracks for a given time window.
     *
     * @param timeRange Spotify time window: {@code short_term}, {@code medium_term}, or {@code long_term}
     * @param limit     number of tracks to return, 1–50
     * @return a page of the user's top {@link TrackDto} items
     */
    @GetMapping("/top-tracks")
    public SpotifyPage<TrackDto> topTracks(
            @RequestParam(defaultValue = "medium_term")
            @Pattern(regexp = TIME_RANGE_PATTERN,
                message = "timeRange must be short_term, medium_term, or long_term") String timeRange,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return spotifyApi.getTopTracks(timeRange, limit);
    }

    /**
     * Returns the authenticated user's top artists for a given time window.
     *
     * @param timeRange Spotify time window: {@code short_term}, {@code medium_term}, or {@code long_term}
     * @param limit     number of artists to return, 1–50
     * @return a page of the user's top {@link ArtistDto} items
     */
    @GetMapping("/top-artists")
    public SpotifyPage<ArtistDto> topArtists(
            @RequestParam(defaultValue = "medium_term")
            @Pattern(regexp = TIME_RANGE_PATTERN,
                message = "timeRange must be short_term, medium_term, or long_term") String timeRange,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return spotifyApi.getTopArtists(timeRange, limit);
    }
}
