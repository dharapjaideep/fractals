package com.spotify.recommender.model.dto.recommendation;

public enum MoodTarget {
    HAPPY(0.3f, 0.1f, 0f, 0f),
    SAD(-0.3f, -0.2f, 0f, 0f),
    ENERGETIC(0.1f, 0.4f, 0f, 0f),
    CHILL(0f, -0.3f, 0f, 0f),
    FOCUSED(0f, 0f, 0.3f, 0f),
    WORKOUT(0f, 0.5f, 0f, 0.3f);

    public final float valenceOffset;
    public final float energyOffset;
    public final float instrumentalnessOffset;
    public final float danceabilityOffset;

    MoodTarget(float valenceOffset, float energyOffset,
               float instrumentalnessOffset, float danceabilityOffset) {
        this.valenceOffset = valenceOffset;
        this.energyOffset = energyOffset;
        this.instrumentalnessOffset = instrumentalnessOffset;
        this.danceabilityOffset = danceabilityOffset;
    }
}
