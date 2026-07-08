package com.spotify.recommender.model.dto.spotify;

import lombok.Data;

import java.util.List;

@Data
public class SpotifyPage<T> {
    private String href;
    private List<T> items;
    private int limit;
    private String next;
    private int offset;
    private String previous;
    private int total;
}
