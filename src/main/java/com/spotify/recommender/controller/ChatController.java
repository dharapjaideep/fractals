package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.chat.ChatRequest;
import com.spotify.recommender.model.dto.chat.ChatResponse;
import com.spotify.recommender.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the conversational music discovery endpoint ({@code POST /api/chat}).
 * Delegates all logic to {@link ChatService}; authentication is enforced by Spring Security
 * on the {@code /api/**} path — no auth check is needed here.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Accepts a natural-language music discovery message from the authenticated user
     * and returns Claude's conversational reply together with resolved Spotify track
     * recommendations and the playlist they were saved to.
     *
     * @param request        the user's message, validated non-blank and max 500 characters
     * @param authentication injected by Spring Security; {@code getName()} returns the Spotify user ID
     * @return the chat response containing the message, tracks, mood, and playlist name
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        return chatService.chat(request, authentication.getName());
    }
}