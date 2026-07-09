package com.spotify.recommender.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.model.dto.placeholder.PlaceholderResponse;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates and refreshes personalized placeholder text for the Fractals chat textarea.
 *
 * <p>Calls Anthropic once to produce three personalized example lines — mood, playlist,
 * and feeling — based on the user's top Spotify tracks and largest owned playlist. Results
 * are stored in {@code user_chat_placeholder_preferences} (keyed by {@code user_id}) so page loads can serve
 * the text instantly without a synchronous AI call on the critical path.
 *
 * <p>Persistence uses {@link JdbcTemplate} with a Postgres {@code ON CONFLICT DO UPDATE}
 * upsert rather than a JPA entity, mirroring {@link EmbeddingService}'s {@code track_embedding}
 * pattern: {@link com.spotify.recommender.config.OAuth2SuccessHandler} triggers regeneration
 * unconditionally on every login, so concurrent logins for the same user (multiple tabs) can
 * race on the same row — a JPA find-then-merge isn't atomic, but a single upsert statement is.
 *
 * <p>Token resolution for background contexts (no live HTTP request) uses
 * {@link OAuth2AuthorizedClientService} to load the user's stored Spotify token directly from
 * the database, then passes it explicitly to the {@code *WithToken} {@link SpotifyApiService}
 * methods — the standard OAuth2-filter WebClient cannot be used here because it requires an
 * active HTTP servlet request.
 */
@Service
public class PlaceholderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderService.class);

    private static final String UPSERT_SQL = """
        INSERT INTO user_chat_placeholder_preferences
               (user_id, placeholder_mood, placeholder_playlist, placeholder_feeling, placeholder_generated_at)
        VALUES (?, ?, ?, ?, now())
        ON CONFLICT (user_id) DO UPDATE SET
            placeholder_mood         = EXCLUDED.placeholder_mood,
            placeholder_playlist     = EXCLUDED.placeholder_playlist,
            placeholder_feeling      = EXCLUDED.placeholder_feeling,
            placeholder_generated_at = now()
        """;

    private static final String SELECT_SQL = """
        SELECT up.placeholder_mood, up.placeholder_playlist, up.placeholder_feeling
        FROM user_chat_placeholder_preferences up
        JOIN app_user au ON au.id = up.user_id
        WHERE au.spotify_id = ?
        """;

    private final SpotifyApiService spotifyApi;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final AppUserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final long maxTokens;

    public PlaceholderService(SpotifyApiService spotifyApi,
                              OAuth2AuthorizedClientService authorizedClientService,
                              AppUserRepository userRepository,
                              JdbcTemplate jdbcTemplate,
                              AnthropicClient anthropicClient,
                              ObjectMapper objectMapper,
                              @Value("${anthropic.model}") String model,
                              @Value("${anthropic.max-tokens}") long maxTokens) {
        this.spotifyApi = spotifyApi;
        this.authorizedClientService = authorizedClientService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Generates personalized placeholder text for the given user and upserts it.
     *
     * <p>Steps: load Spotify token → fetch top tracks + largest owned playlist → call
     * Anthropic for a single JSON object with three lines → parse → upsert.
     * On any error — missing token, Spotify failure, Anthropic failure, unparseable
     * response — logs a warning and leaves the existing row untouched. Never throws.
     *
     * @param user the user whose placeholder should be regenerated
     */
    public void regenerate(AppUser user) {
        try {
            OAuth2AuthorizedClient client =
                authorizedClientService.loadAuthorizedClient("spotify", user.getSpotifyId());
            if (client == null) {
                log.warn("No Spotify token found for user {}; skipping placeholder generation",
                    user.getSpotifyId());
                return;
            }
            String accessToken = client.getAccessToken().getTokenValue();

            List<TrackDto> topTracks = fetchTopTracks(accessToken, user.getSpotifyId());
            String largestPlaylistName = fetchLargestOwnedPlaylistName(accessToken, user.getSpotifyId());

            String prompt = buildPrompt(topTracks, largestPlaylistName);
            MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system("You are a music personalization assistant. " +
                        "Generate short, evocative placeholder text " +
                        "for a music discovery interface. " +
                        "Only generate music-related content.")
                .addUserMessage(prompt)
                .build();
            Message message = anthropicClient.messages().create(params);
            String rawResponse = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

            PlaceholderFields fields = parsePayload(rawResponse);
            if (fields == null) {
                log.warn("Anthropic returned unparseable placeholder JSON for user {}; leaving existing value",
                    user.getSpotifyId());
                return;
            }

            jdbcTemplate.update(UPSERT_SQL, user.getId(), fields.mood(), fields.playlist(), fields.feeling());
            log.info("Generated placeholder for user {}", user.getSpotifyId());

        } catch (Exception e) {
            // Fail-safe: existing user_chat_placeholder_preferences row is NOT cleared or overwritten on error
            log.warn("Placeholder generation failed for user {}; leaving existing value",
                user.getSpotifyId(), e);
        }
    }

    /**
     * Asynchronous wrapper for {@link #regenerate(AppUser)}.
     *
     * <p>Dispatched by {@link com.spotify.recommender.config.OAuth2SuccessHandler} on every
     * login, not just first login — {@code user_chat_placeholder_preferences} is upserted each time so
     * personalization stays fresh as the user's taste evolves. Runs on Spring's async thread
     * pool so the OAuth redirect is not blocked by a Spotify + Anthropic round-trip. All errors
     * are handled inside {@link #regenerate} — this method never throws.
     *
     * @param user the user who just logged in
     */
    @Async
    public void regenerateAsync(AppUser user) {
        regenerate(user);
    }

    /**
     * Daily scheduled job that refreshes placeholder text for all users.
     *
     * <p>Runs at 03:00 server time. Each user is processed independently — one failure does
     * not stop the others. Logs a summary at completion.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void refreshAllPlaceholders() {
        List<AppUser> users = userRepository.findAll();
        int success = 0;
        for (AppUser user : users) {
            try {
                regenerate(user);
                success++;
            } catch (Exception e) {
                // Defence in depth: regenerate() already catches all exceptions internally,
                // but this outer catch ensures one unexpected failure never aborts the loop.
                log.warn("Unexpected error refreshing placeholder for user {} in daily job",
                    user.getSpotifyId(), e);
            }
        }
        log.info("Refreshed {}/{} placeholders", success, users.size());
    }

    /**
     * Returns a {@link PlaceholderResponse} for the given Spotify user ID.
     *
     * <p>{@code ready} is {@code true} only when a {@code user_chat_placeholder_preferences} row exists.
     * {@code ready=false} means async generation is still in progress or has not yet run;
     * the frontend leaves its interim example text in place.
     *
     * @param spotifyId the authenticated user's Spotify ID
     * @return response indicating whether personalized text is available
     */
    public PlaceholderResponse getPlaceholder(String spotifyId) {
        List<PlaceholderResponse> rows = jdbcTemplate.query(SELECT_SQL,
            (rs, rowNum) -> new PlaceholderResponse(
                rs.getString("placeholder_mood"),
                rs.getString("placeholder_playlist"),
                rs.getString("placeholder_feeling"),
                true),
            spotifyId);
        return rows.isEmpty() ? new PlaceholderResponse(null, null, null, false) : rows.get(0);
    }

    private List<TrackDto> fetchTopTracks(String accessToken, String spotifyId) {
        try {
            SpotifyPage<TrackDto> page = spotifyApi.getTopTracksWithToken(accessToken, "medium_term", 10);
            return (page != null && page.getItems() != null) ? page.getItems() : List.of();
        } catch (RuntimeException e) {
            log.warn("Failed to fetch top tracks for placeholder (user {}); continuing without",
                spotifyId, e);
            return List.of();
        }
    }

    /**
     * Paginates the user's owned playlists and returns the name of the one with the most tracks.
     *
     * @return the largest playlist's name, or null if the user has none or the fetch fails
     */
    private String fetchLargestOwnedPlaylistName(String accessToken, String spotifyId) {
        String largestName = null;
        int largestCount = -1;
        int offset = 0;
        final int PAGE = 50;
        try {
            while (true) {
                SpotifyPage<PlaylistDto> page =
                    spotifyApi.getPlaylistsWithToken(accessToken, spotifyId, offset, PAGE);
                if (page == null || page.getItems() == null) break;
                for (PlaylistDto playlist : page.getItems()) {
                    if (playlist.getName() == null || playlist.getTracks() == null) continue;
                    int count = playlist.getTracks().getTotal();
                    if (count > largestCount) {
                        largestCount = count;
                        largestName = playlist.getName();
                    }
                }
                // Advance by the full requested page size, not the filtered item count — the owner
                // filter in getPlaylistsWithToken() can return fewer items than PAGE on a non-last page.
                offset += PAGE;
                // Use Spotify's next-cursor rather than item count to detect the final page.
                if (page.getNext() == null) break;
            }
        } catch (RuntimeException e) {
            log.warn("Failed to fetch playlists for placeholder (user {}); continuing without",
                spotifyId, e);
        }
        return largestName;
    }

    private String buildPrompt(List<TrackDto> topTracks, String largestPlaylistName) {
        StringBuilder sb = new StringBuilder("""
            Generate personalized example prompts for a music discovery chat text box, based on \
            this user's Spotify taste. Return a single JSON object and nothing else — no markdown, \
            no code fences, no text before or after — with exactly these three fields:

            {"mood": "a short mood-based example request (8-12 words), e.g. 'something to listen to at 2am'", \
            "playlist": "a short example request naming their playlist, in the style 'something like my X playlist'", \
            "feeling": "an evocative, poetic feeling-based example (max 14 words), e.g. 'rain on train windows and something left unsaid'"}

            Each should sound like something the user might actually type — specific and inviting, not generic.""");
        if (!topTracks.isEmpty()) {
            sb.append("\n\nThe user's top tracks: ");
            for (int i = 0; i < topTracks.size(); i++) {
                TrackDto t = topTracks.get(i);
                String artists = t.getArtists().stream()
                    .map(TrackDto.ArtistRef::getName)
                    .collect(Collectors.joining(", "));
                sb.append(t.getName()).append(" by ").append(artists);
                if (i < topTracks.size() - 1) sb.append("; ");
            }
            sb.append('.');
        }
        if (largestPlaylistName != null) {
            sb.append("\n\nTheir largest playlist is called '").append(largestPlaylistName)
              .append("'. Use this exact name for the 'playlist' field, e.g. \"something like my ")
              .append(largestPlaylistName).append(" playlist\".");
        }
        return sb.toString();
    }

    private record PlaceholderFields(String mood, String playlist, String feeling) {}

    private PlaceholderFields parsePayload(String rawResponse) {
        try {
            String cleaned = stripMarkdownFence(rawResponse);
            // Claude occasionally emits reasoning prose before the JSON object despite the
            // prompt instruction. Strip everything before the first '{' so objectMapper doesn't fail.
            int jsonStart = cleaned.indexOf('{');
            if (jsonStart > 0) {
                cleaned = cleaned.substring(jsonStart);
            }
            JsonNode root = objectMapper.readTree(cleaned);
            String mood = root.path("mood").asText(null);
            String playlist = root.path("playlist").asText(null);
            String feeling = root.path("feeling").asText(null);
            if (mood == null || playlist == null || feeling == null
                    || mood.isBlank() || playlist.isBlank() || feeling.isBlank()) {
                return null;
            }
            return new PlaceholderFields(mood, playlist, feeling);
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic placeholder response as JSON", e);
            return null;
        }
    }

    // Defence in depth alongside the prompt instruction: Claude sometimes wraps its JSON
    // response in a ```json ... ``` code fence despite being told not to.
    private static String stripMarkdownFence(String rawResponse) {
        String cleaned = rawResponse.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```")).trim();
        }
        return cleaned;
    }
}
