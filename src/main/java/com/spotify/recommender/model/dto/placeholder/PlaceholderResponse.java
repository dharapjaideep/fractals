package com.spotify.recommender.model.dto.placeholder;

/**
 * Response DTO for {@code GET /api/chat/placeholder}.
 *
 * @param moodExample     personalized "based on mood" example line (null when not yet generated)
 * @param playlistExample personalized "based on a playlist" example line, naming the user's
 *                        largest owned playlist (null when not yet generated)
 * @param feelingLine     personalized "based on a feeling" evocative example line (null when
 *                        not yet generated)
 * @param ready           true when all three personalized lines are available, false while async
 *                        generation is still in progress or has not been triggered yet
 */
public record PlaceholderResponse(String moodExample, String playlistExample, String feelingLine, boolean ready) {}
