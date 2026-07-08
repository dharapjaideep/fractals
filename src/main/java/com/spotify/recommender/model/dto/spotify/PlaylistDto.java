package com.spotify.recommender.model.dto.spotify;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO for a Spotify playlist object returned by {@code GET /me/playlists} and related endpoints.
 */
@Data
public class PlaylistDto {
    private String id;
    private String name;
    private String description;
    private boolean collaborative;
    @JsonProperty("public")
    private Boolean isPublic;
    private TracksRef tracks;
    /** The Spotify user who owns this playlist. Used to filter out followed (non-owned) playlists. */
    private Owner owner;

    @Data
    public static class TracksRef {
        private int total;
    }

    /** Nested owner object from the Spotify API response. */
    @Data
    public static class Owner {
        private String id;
        @JsonProperty("display_name")
        private String displayName;
    }
}
