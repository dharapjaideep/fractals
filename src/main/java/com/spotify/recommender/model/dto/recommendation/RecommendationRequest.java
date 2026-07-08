package com.spotify.recommender.model.dto.recommendation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RecommendationRequest {
    @Min(1) @Max(50)
    private int limit = 20;
    private Float energyBoost;
    private MoodTarget moodTarget;
    private String excludePlaylistId;
}
