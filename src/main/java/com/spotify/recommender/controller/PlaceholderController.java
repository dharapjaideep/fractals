package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.placeholder.PlaceholderResponse;
import com.spotify.recommender.service.PlaceholderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for {@code GET /api/chat/placeholder}.
 *
 * <p>Returns the stored personalized placeholder text for the authenticated user, or a static
 * fallback if async generation has not yet completed. Authentication is enforced by Spring
 * Security's existing {@code /api/**} rule — no auth check needed here.
 */
@RestController
@RequestMapping("/api/chat")
public class PlaceholderController {

    private final PlaceholderService placeholderService;

    public PlaceholderController(PlaceholderService placeholderService) {
        this.placeholderService = placeholderService;
    }

    /**
     * Returns a JSON response indicating whether a personalized placeholder is ready.
     *
     * <p>When {@code ready} is {@code false}, the frontend leaves its "Analyzing your
     * playlists..." interim placeholder in place. When {@code ready} is {@code true},
     * {@code placeholder} contains the text for the fourth line of the multi-line structure.
     *
     * @param authentication injected by Spring Security; {@code getName()} returns the Spotify user ID
     * @return {@link PlaceholderResponse} serialised as JSON
     */
    @GetMapping("/placeholder")
    public PlaceholderResponse getPlaceholder(Authentication authentication) {
        return placeholderService.getPlaceholder(authentication.getName());
    }
}
