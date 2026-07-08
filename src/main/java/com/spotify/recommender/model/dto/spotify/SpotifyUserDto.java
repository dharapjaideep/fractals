package com.spotify.recommender.model.dto.spotify;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SpotifyUserDto {
    private String id;
    @JsonProperty("display_name")
    private String displayName;
    private String email;
    private String country;
    private String product;
    private Followers followers;

    @Data
    public static class Followers {
        private int total;
    }
}
