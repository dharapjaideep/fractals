package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.service.PlaylistService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/playlists")
@Validated
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping
    public SpotifyPage<PlaylistDto> playlists(
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return playlistService.getPlaylists(offset, limit);
    }

    @GetMapping("/{id}/tracks")
    public SpotifyPage<PlaylistTrackDto> playlistTracks(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return playlistService.getPlaylistTracks(id, offset, limit);
    }
}
