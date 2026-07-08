package com.spotify.recommender.model.dto.spotify;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PlaylistTrackDto {
    @JsonProperty("added_at")
    private String addedAt;
    private TrackDto track;
}
