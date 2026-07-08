package com.spotify.recommender.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity persisting each conversational exchange to the {@code chat_log} table.
 *
 * <p>{@code userId} is a foreign key to {@code app_user.id}; a log entry is only written
 * when the user exists in the database (so {@code userId} is never null at persist time).
 * {@code response} holds the raw Anthropic response text and may be {@code null} when the
 * Anthropic call itself failed (the user message is still captured in that case).
 * {@code createdAt} is set by {@link #onCreate()} before first persist and is immutable thereafter.
 */
@Entity
@Table(name = "chat_log")
@Getter
@Setter
@NoArgsConstructor
public class ChatLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(columnDefinition = "TEXT")
    private String response;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}