package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.recommendation.RecommendationRequest;
import com.spotify.recommender.model.dto.recommendation.RecommendationResponse;
import com.spotify.recommender.service.RecommendationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the AI-powered music recommendation endpoint ({@code POST /api/recommendations}).
 *
 * <p>Thin delegator — all business logic lives in {@link RecommendationService}.
 * Authentication is enforced by Spring Security on the {@code /api/**} path; no
 * auth check is needed here.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Generates personalized track recommendations for the authenticated user.
     *
     * @param request optional recommendation parameters (mood target, energy boost, limit);
     *                if the body is omitted, {@link RecommendationRequest} defaults are used
     * @return a {@link RecommendationResponse} containing ranked track suggestions
     */
    @PostMapping
    public RecommendationResponse recommend(@Valid @RequestBody(required = false) RecommendationRequest request) {
        return recommendationService.recommend(request);
    }

    /**
     * Convenience endpoint for quick recommendations with all-default parameters.
     * Equivalent to {@code POST /api/recommendations} with an empty request body.
     *
     * @return a {@link RecommendationResponse} containing ranked track suggestions with default settings
     */
    @GetMapping("/quick")
    public RecommendationResponse quick() {
        return recommendationService.recommend(new RecommendationRequest());
    }
}
