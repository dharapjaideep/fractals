package com.spotify.recommender.util;

import com.spotify.recommender.model.dto.recommendation.MoodTarget;

public final class PlaylistNameUtil {

    private PlaylistNameUtil() {}

    public static String forMood(MoodTarget mood, String fallback) {
        return mood != null ? "Fractals-" + mood.name() : fallback;
    }
}