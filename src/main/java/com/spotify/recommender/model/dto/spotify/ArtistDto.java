package com.spotify.recommender.model.dto.spotify;

import lombok.Data;

import java.util.List;

@Data
public class ArtistDto {
    private String id;
    private String name;
    private int popularity;
    private List<String> genres;
    private Followers followers;

    @Data
    public static class Followers {
        private int total;
    }
}
