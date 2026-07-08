package com.spotify.recommender.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Spring configuration for the Anthropic API client.
 *
 * <p>Exposes a single {@link AnthropicClient} bean built from the {@code ANTHROPIC_API_KEY}
 * environment variable. The bean is shared by {@link com.spotify.recommender.service.ChatService}
 * and any other component that needs to call the Anthropic API.
 */
@Configuration
public class AnthropicConfig {

    /**
     * Builds and returns the shared {@link AnthropicClient} bean.
     *
     * <p>Validates that the key is present and non-blank before constructing the client —
     * defence in depth alongside the no-default placeholder in {@code application.yml}.
     * Failing closed at startup (rather than at the first API call) makes the
     * misconfiguration immediately visible instead of silently degrading to empty
     * recommendations or chat responses.
     *
     * @param apiKey the Anthropic API key, injected from {@code anthropic.api-key}
     *               (populated from the {@code ANTHROPIC_API_KEY} environment variable)
     * @return a configured {@link AnthropicClient}
     * @throws IllegalStateException if {@code apiKey} is missing or blank
     */
    @Bean
    public AnthropicClient anthropicClient(@Value("${anthropic.api-key}") String apiKey) {
        // Defence in depth alongside the no-default placeholder in application.yml: even if the
        // key is supplied but blank/whitespace, fail closed at startup rather than building a
        // client that only fails (and gets swallowed into empty recommendations) at runtime.
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                "anthropic.api-key is missing or blank. Set the ANTHROPIC_API_KEY environment variable.");
        }
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build();
    }
}