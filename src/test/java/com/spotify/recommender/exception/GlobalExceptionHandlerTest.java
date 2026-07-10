package com.spotify.recommender.exception;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionTestController.class)
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
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @Test
    @WithMockUser
    void rateLimitException_returns429WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/rate-limit"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
            .andExpect(jsonPath("$.error").value("rate_limited"))
            .andExpect(jsonPath("$.retryAfterSeconds").value(5));
    }

    @Test
    @WithMockUser
    void webClientResponseException_propagatesUpstreamStatus() throws Exception {
        mockMvc.perform(get("/test/api-error"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("spotify_api_error"))
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser
    void webClientResponseException_propagatesUpstream5xxStatus() throws Exception {
        mockMvc.perform(get("/test/api-error-5xx"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("spotify_api_error"))
            .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @WithMockUser
    void constraintViolationException_returns400InsteadOf500() throws Exception {
        mockMvc.perform(get("/test/constraint-violation").param("value", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }
}