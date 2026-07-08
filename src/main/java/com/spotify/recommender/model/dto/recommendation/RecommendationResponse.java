package com.spotify.recommender.model.dto.recommendation;

import com.spotify.recommender.model.dto.spotify.TrackDto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RecommendationResponse {
    private List<RankedTrack> tracks;
    private int total;

    @Data
    @AllArgsConstructor
    public static class RankedTrack {
        private TrackDto track;
        private double score;
    }
}
