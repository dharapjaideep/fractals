package com.spotify.recommender.repository;

import com.spotify.recommender.model.entity.ChatLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatLogRepository extends JpaRepository<ChatLog, UUID> {

    /**
     * Returns the 10 most recent chat log entries for a user, newest first — used to build
     * conversational memory context for the Anthropic API call.
     *
     * @param userId the {@code app_user.id} primary key (not the Spotify ID)
     * @return up to 10 {@link ChatLog} entries ordered by {@code created_at} descending
     */
    List<ChatLog> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
}