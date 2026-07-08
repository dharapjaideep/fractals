package com.spotify.recommender.model.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/chat}.
 * Carries the user's natural-language input describing their mood or music intent.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {
    /** The user's message; must be non-blank and at most 500 characters. */
    @NotBlank
    @Size(max = 500, message = "Message must be under 500 characters")
    private String message;
}