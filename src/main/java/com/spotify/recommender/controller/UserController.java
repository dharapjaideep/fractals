package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.spotify.ArtistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.SpotifyApiService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {

    private final SpotifyApiService spotifyApi;

    public UserController(SpotifyApiService spotifyApi) {
        this.spotifyApi = spotifyApi;
    }

    @GetMapping("/profile")
    public SpotifyUserDto profile() {
        return spotifyApi.getProfile();
    }

    @GetMapping("/top-tracks")
    public SpotifyPage<TrackDto> topTracks(
            @RequestParam(defaultValue = "medium_term") String timeRange,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return spotifyApi.getTopTracks(timeRange, limit);
    }

    @GetMapping("/top-artists")
    public SpotifyPage<ArtistDto> topArtists(
            @RequestParam(defaultValue = "medium_term") String timeRange,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return spotifyApi.getTopArtists(timeRange, limit);
    }
}
