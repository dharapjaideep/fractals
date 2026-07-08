package com.spotify.recommender.model.dto.spotify;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class TrackDto {
    private String id;
    private String name;
    private int popularity;
    @JsonProperty("duration_ms")
    private int durationMs;
    private boolean explicit;
    private String uri;
    private List<ArtistRef> artists;
    private AlbumRef album;

    @Data
    public static class ArtistRef {
        private String id;
        private String name;
    }

    @Data
    public static class AlbumRef {
        private String id;
        private String name;
        @JsonProperty("release_date")
        private String releaseDate;
        private List<Image> images;
    }

    @Data
    public static class Image {
        private String url;
        private int width;
        private int height;
    }
}
