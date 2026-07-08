package com.spotify.recommender.config;

import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import com.spotify.recommender.service.EmbeddingService;
import com.spotify.recommender.service.PlaceholderService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Post-login hook that upserts the authenticated Spotify user into the local {@code app_user} table.
 *
 * <p>Extends {@link SimpleUrlAuthenticationSuccessHandler} so that after the upsert the handler
 * delegates to the standard redirect-to-saved-URL / default-target-URL flow. The default target
 * is set to {@code "/"} in the constructor so users land on the Fractals frontend after Spotify login.
 *
 * <p>Token storage is intentionally not handled here — tokens are managed exclusively by
 * {@link JdbcOAuth2AuthorizedClientService} (configured in {@link WebClientConfig}).
 * This handler concerns itself only with profile data: Spotify ID, display name, and email.
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AppUserRepository userRepository;
    private final PlaceholderService placeholderService;
    // EmbeddingService kept for RAG re-enable — see FEATURES-TODO.md — currently disabled
    // pending privacy review.
    private final EmbeddingService embeddingService;

    public OAuth2SuccessHandler(AppUserRepository userRepository,
                                PlaceholderService placeholderService,
                                EmbeddingService embeddingService) {
        this.userRepository = userRepository;
        this.placeholderService = placeholderService;
        this.embeddingService = embeddingService;
        setDefaultTargetUrl("/");
    }

    /**
     * Upserts the authenticated user's profile in {@code app_user} and then delegates to the
     * superclass to perform the post-login redirect to {@code "/"}.
     *
     * <p>An existing {@code app_user} row is found and updated (rather than duplicated) to keep
     * the user's primary key and any associated data stable across multiple logins. A new row
     * is created on first login. On every login, unconditionally, an asynchronous call to
     * {@link com.spotify.recommender.service.PlaceholderService#regenerateAsync} is triggered
     * to refresh the personalized textarea placeholder without blocking the OAuth redirect —
     * not just on first login, so personalization stays current as the user's taste evolves.
     * {@code user_chat_placeholder_preferences} is upserted each time, so repeated logins simply overwrite the
     * previous personalization rather than accumulating rows.
     *
     * <p>Email is read defensively as {@code Object} and converted to {@code String} because
     * Spotify's token may omit the email attribute for users who have not granted email access;
     * in that case {@code null} is stored rather than throwing an NPE.
     *
     * @param request        the HTTP request
     * @param response       the HTTP response
     * @param authentication the successful authentication; principal must be an {@link OAuth2User}
     *                       with Spotify profile attributes ({@code id}, {@code display_name},
     *                       {@code email})
     * @throws IOException      if the redirect response cannot be written
     * @throws ServletException if the superclass handler encounters a servlet error
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();

        String spotifyId = principal.getAttribute("id");
        String displayName = principal.getAttribute("display_name");
        Object emailAttr = principal.getAttribute("email");
        String email = emailAttr != null ? emailAttr.toString() : null;

        AppUser user = userRepository.findBySpotifyId(spotifyId).orElseGet(AppUser::new);
        user.setSpotifyId(spotifyId);
        user.setDisplayName(displayName);
        user.setEmail(email);
        userRepository.save(user);

        // Unconditional on every login (not gated on prior state) — token is in the DB by this
        // point (saved during the code exchange that precedes this callback), so regenerateAsync
        // can load it without a servlet request context.
        placeholderService.regenerateAsync(user);

        // RAG embedding pipeline disabled pending privacy review.
        // Sending users' full Spotify libraries to OpenAI API requires explicit user consent
        // and privacy policy coverage before enabling.
        // See FEATURES-TODO.md and privacy discussion.
        // The infrastructure (EmbeddingService, schema, pgvector) remains in place for when
        // this is properly addressed.
        //
        // if (!embeddingService.hasEmbeddings(user.getId())) {
        //     embeddingService.buildLibraryAsync(user);
        // }

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
