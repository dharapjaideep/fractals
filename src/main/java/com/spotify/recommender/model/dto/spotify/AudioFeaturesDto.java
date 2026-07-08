package com.spotify.recommender.model.dto.spotify;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AudioFeaturesDto {
    private String id;
    private float danceability;
    private float energy;
    private float valence;
    private float acousticness;
    private float instrumentalness;
    private float liveness;
    private float speechiness;
    private float tempo;
    private float loudness;
    private int key;
    private int mode;
    @JsonProperty("time_signature")
    private int timeSignature;
    @JsonProperty("duration_ms")
    private int durationMs;
    private String uri;
}
