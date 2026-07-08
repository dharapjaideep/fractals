package com.spotify.recommender.model.dto.chat;

import com.spotify.recommender.model.dto.recommendation.RecommendationResponse.RankedTrack;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Response body for {@code POST /api/chat}.
 *
 * <ul>
 *   <li>{@code message} — Claude's conversational reply.</li>
 *   <li>{@code mood} — the raw mood string Claude detected (an enum name from
 *       {@link com.spotify.recommender.model.dto.recommendation.MoodTarget}, e.g. {@code "CHILL"}),
 *       or {@code null} if Claude omitted it or returned an unrecognised value.</li>
 *   <li>{@code tracks} — Spotify tracks resolved from Claude's suggestions.</li>
 *   <li>{@code playlistName} — the Spotify playlist the tracks were saved to, or {@code null}
 *       if the track list was empty or the save failed.</li>
 *   <li>{@code playlistId} — the Spotify ID of that playlist, or {@code null} under the same
 *       conditions as {@code playlistName}; lets the frontend deep-link directly to it.</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class ChatResponse {
    private String message;
    private String mood;
    private List<RankedTrack> tracks;
    private String playlistName;
    private String playlistId;
}