package com.spotify.recommender.model.dto.spotify;

import lombok.Data;

import java.util.List;

@Data
public class SpotifySearchResponse {
    private Tracks tracks;

    @Data
    public static class Tracks {
        private List<TrackDto> items;
    }
}
