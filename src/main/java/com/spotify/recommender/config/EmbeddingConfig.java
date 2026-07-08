package com.spotify.recommender.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Provides the {@link EmbeddingModel} bean used by {@link com.spotify.recommender.service.EmbeddingService}
 * for RAG semantic retrieval.
 *
 * <p>The bean is created manually (rather than via Spring AI auto-configuration) so that the app
 * boots cleanly when {@code OPENAI_API_KEY} is absent. When the key is not set, a
 * {@link DisabledEmbeddingModel} is returned and {@code EmbeddingService} degrades to returning
 * empty retrieval results, preserving the existing top-tracks context path in ChatService.
 *
 * <p>{@code spring-ai-openai} 1.1.8 ships no Spring Boot auto-configuration, so there is no
 * competing bean to guard against — this class is the sole provider of {@link EmbeddingModel}.
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /**
     * Creates an {@link OpenAiEmbeddingModel} configured for {@code text-embedding-3-small} when
     * {@code OPENAI_API_KEY} is set, or a no-op model that always returns empty results when the
     * key is absent.
     *
     * <p>Using {@code text-embedding-3-small}: 1536-dimension output, cost-effective, sufficient
     * recall quality for personal Spotify library sizes (200–2000 tracks per user).
     *
     * @param apiKey the OpenAI API key from environment; empty string when not configured
     * @return a fully configured {@link OpenAiEmbeddingModel}, or a {@link DisabledEmbeddingModel}
     */
    @Bean
    public EmbeddingModel fractalsEmbeddingModel(@Value("${OPENAI_API_KEY:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            // RAG disabled: EmbeddingService detects this via the same key check and skips all
            // embedding calls. The DisabledEmbeddingModel is a safety net — it should never be
            // called in practice when ragEnabled=false, but returns empty rather than throwing
            // if called unexpectedly.
            log.warn("OPENAI_API_KEY not set — RAG disabled, falling back to top-tracks context");
            return new DisabledEmbeddingModel();
        }
        OpenAiApi openAiApi = OpenAiApi.builder().apiKey(apiKey).build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model("text-embedding-3-small")
            .build();
        log.info("RAG enabled — using OpenAI text-embedding-3-small for semantic retrieval");
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    /**
     * Returned when {@code OPENAI_API_KEY} is not configured.
     *
     * <p>Always returns an empty {@link EmbeddingResponse}. {@link com.spotify.recommender.service.EmbeddingService}
     * checks {@code ragEnabled} before calling the model, so this implementation is a safety net
     * rather than an active code path. It prevents NPEs if the check is ever bypassed.
     */
    static final class DisabledEmbeddingModel extends AbstractEmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(List.of());
        }

        @Override
        public float[] embed(Document document) {
            return new float[0];
        }

        @Override
        public int dimensions() {
            return 0;
        }
    }
}
