package com.spotify.recommender.util;

import com.spotify.recommender.model.dto.recommendation.MoodTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistNameUtilTest {

    @Test
    void forMood_returnsMoodPrefixedName_whenMoodIsNotNull() {
        assertThat(PlaylistNameUtil.forMood(MoodTarget.CHILL, "Fractals-Music"))
            .isEqualTo("Fractals-CHILL");
    }

    @Test
    void forMood_returnsFallback_whenMoodIsNull() {
        assertThat(PlaylistNameUtil.forMood(null, "Fractals-Music"))
            .isEqualTo("Fractals-Music");
    }

    @Test
    void forMood_usesMoodEnumNameForAllValues() {
        for (MoodTarget mood : MoodTarget.values()) {
            assertThat(PlaylistNameUtil.forMood(mood, "fallback"))
                .isEqualTo("Fractals-" + mood.name());
        }
    }
}