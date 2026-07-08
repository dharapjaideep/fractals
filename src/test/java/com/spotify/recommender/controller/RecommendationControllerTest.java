package com.spotify.recommender.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.dto.recommendation.MoodTarget;
import com.spotify.recommender.model.dto.recommendation.RecommendationRequest;
import com.spotify.recommender.model.dto.recommendation.RecommendationResponse;
import com.spotify.recommender.model.dto.recommendation.RecommendationResponse.RankedTrack;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecommendationController.class)
@TestPropertySource(properties = {
    // Minimal fake registration so SecurityConfig's oauth2Login() can initialize
    "spring.security.oauth2.client.registration.spotify.client-id=test-id",
    "spring.security.oauth2.client.registration.spotify.client-secret=test-secret",
    "spring.security.oauth2.client.registration.spotify.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.spotify.redirect-uri=http://localhost/cb",
    "spring.security.oauth2.client.registration.spotify.scope=openid",
    "spring.security.oauth2.client.provider.spotify.authorization-uri=https://accounts.spotify.com/authorize",
    "spring.security.oauth2.client.provider.spotify.token-uri=https://accounts.spotify.com/api/token",
    "spring.security.oauth2.client.provider.spotify.user-info-uri=https://api.spotify.com/v1/me",
    "spring.security.oauth2.client.provider.spotify.user-name-attribute=id"
})
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RecommendationService recommendationService;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    // ── GET /api/recommendations/quick ────────────────────────────────────────

    @Test
    @WithMockUser
    void quick_returnsRankedTracks() throws Exception {
        when(recommendationService.recommend(any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/recommendations/quick"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tracks").isArray())
            .andExpect(jsonPath("$.tracks[0].track.id").value("t1"))
            .andExpect(jsonPath("$.tracks[0].score").value(0.95))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void quick_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/recommendations/quick"))
            .andExpect(status().is3xxRedirection());
    }

    // ── POST /api/recommendations ─────────────────────────────────────────────

    @Test
    @WithMockUser
    void recommend_withBody_delegatesToService() throws Exception {
        RecommendationRequest req = new RecommendationRequest();
        req.setLimit(10);
        req.setMoodTarget(MoodTarget.HAPPY);
        when(recommendationService.recommend(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/recommendations")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());

        verify(recommendationService).recommend(any(RecommendationRequest.class));
    }

    @Test
    @WithMockUser
    void recommend_emptyBody_usesDefaults() throws Exception {
        when(recommendationService.recommend(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/recommendations")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void recommend_limitExceedsMax_returns400() throws Exception {
        mockMvc.perform(post("/api/recommendations")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\": 999}"))  // @Max(50) violated
            .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RecommendationResponse sampleResponse() {
        TrackDto track = new TrackDto();
        track.setId("t1");
        track.setName("Sample Track");
        return new RecommendationResponse(List.of(new RankedTrack(track, 0.95)), 1);
    }
}
