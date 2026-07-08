package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds and maintains a per-user semantic index of Spotify library tracks using OpenAI embeddings
 * stored in a pgvector {@code track_embedding} table.
 *
 * <p>On first login, {@link #buildLibraryAsync} crawls the user's playlists and top tracks, embeds
 * each track as a single-line text string ("Song by Artist, from Album, in playlist: Playlist"),
 * and upserts the resulting 1536-dimension vectors. Nightly, {@link #refreshAllEmbeddings} calls
 * {@link #updateLibrary} for each user to pick up new tracks added since the last run.
 *
 * <p>At chat time, {@link #findRelevantTracks} embeds the user's message and returns the
 * {@code k} nearest library tracks via cosine similarity, giving {@link ChatService} a semantically
 * grounded context window instead of the blunt top-10 signal.
 *
 * <p>When {@code OPENAI_API_KEY} is absent, {@link #ragEnabled} is {@code false} and all methods
 * return immediately without touching the database or the embedding model. The existing top-tracks
 * context path in {@link ChatService} is preserved.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String UPSERT_SQL = """
        INSERT INTO track_embedding
               (user_id, spotify_track_id, track_text, embedding, playlist_names, last_updated)
        VALUES (?, ?, ?, CAST(? AS vector), ?, now())
        ON CONFLICT (user_id, spotify_track_id) DO UPDATE SET
            track_text     = EXCLUDED.track_text,
            embedding      = EXCLUDED.embedding,
            playlist_names = EXCLUDED.playlist_names,
            last_updated   = now()
        """;

    private final SpotifyApiService spotifyApi;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final AppUserRepository userRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final boolean ragEnabled;
    private final int batchSize;
    // topK kept for RAG re-enable — not currently read; callers pass k explicitly instead.
    private final int topK;

    public EmbeddingService(SpotifyApiService spotifyApi,
                            EmbeddingModel embeddingModel,
                            JdbcTemplate jdbcTemplate,
                            AppUserRepository userRepository,
                            OAuth2AuthorizedClientService authorizedClientService,
                            @Value("${fractals.rag.batch-size:100}") int batchSize,
                            @Value("${fractals.rag.top-k:35}") int topK,
                            @Value("${OPENAI_API_KEY:}") String openAiApiKey) {
        this.spotifyApi = spotifyApi;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.authorizedClientService = authorizedClientService;
        this.batchSize = batchSize;
        this.topK = topK;
        // Mirror the same check used in EmbeddingConfig so ragEnabled is always consistent
        // with which EmbeddingModel bean was created.
        this.ragEnabled = openAiApiKey != null && !openAiApiKey.isBlank();
        if (ragEnabled) {
            log.info("EmbeddingService: RAG enabled (batch-size={}, top-k={})", batchSize, topK);
        }
    }

    /**
     * Full library crawl: fetches all playlists and top tracks for the user, embeds each track,
     * and upserts into {@code track_embedding}. Runs synchronously — call
     * {@link #buildLibraryAsync} to avoid blocking a caller thread.
     *
     * @param user the user whose library to index; must have a valid Spotify token in the DB
     */
    public void buildLibrary(AppUser user) {
        if (!ragEnabled) return;
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient("spotify", user.getSpotifyId());
        if (client == null) {
            log.warn("No Spotify token for user {}; skipping embedding library build", user.getSpotifyId());
            return;
        }
        String accessToken = client.getAccessToken().getTokenValue();
        Map<String, TrackDto> trackById = new LinkedHashMap<>();
        Map<String, Set<String>> trackToPlaylists = new LinkedHashMap<>();
        fetchAllPlaylistTracks(accessToken, user.getSpotifyId(), trackById, trackToPlaylists);
        addTopTracks(accessToken, user.getSpotifyId(), trackById, trackToPlaylists);
        List<LibraryTrack> tracks = buildLibraryTracks(trackById, trackToPlaylists);
        int embedded = embedAndUpsert(user.getId(), tracks);
        log.info("Built embeddings for {}/{} tracks for user {}", embedded, tracks.size(), user.getSpotifyId());
    }

    /**
     * Asynchronous wrapper around {@link #buildLibrary}. Suitable for first-login triggers
     * from {@link com.spotify.recommender.config.OAuth2SuccessHandler} where blocking would
     * delay the OAuth redirect.
     *
     * @param user the user whose library to index
     */
    @Async
    public void buildLibraryAsync(AppUser user) {
        buildLibrary(user);
    }

    /**
     * Incremental update: fetches the full library from Spotify, then embeds and upserts only
     * tracks that are new or whose embedding is more than 24 hours old. Used by the daily
     * scheduled job and skips users whose tokens have expired.
     *
     * @param user the user to update; must have a valid Spotify token in the DB
     */
    public void updateLibrary(AppUser user) {
        if (!ragEnabled) return;
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient("spotify", user.getSpotifyId());
        if (client == null) {
            log.warn("No Spotify token for user {}; skipping embedding library update", user.getSpotifyId());
            return;
        }
        String accessToken = client.getAccessToken().getTokenValue();
        Map<String, Instant> existing = loadExistingTimestamps(user.getId());
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        Map<String, TrackDto> trackById = new LinkedHashMap<>();
        Map<String, Set<String>> trackToPlaylists = new LinkedHashMap<>();
        fetchAllPlaylistTracks(accessToken, user.getSpotifyId(), trackById, trackToPlaylists);
        addTopTracks(accessToken, user.getSpotifyId(), trackById, trackToPlaylists);
        List<LibraryTrack> all = buildLibraryTracks(trackById, trackToPlaylists);
        List<LibraryTrack> toProcess = all.stream()
            .filter(t -> {
                Instant lastUpdated = existing.get(t.spotifyTrackId());
                return lastUpdated == null || lastUpdated.isBefore(cutoff);
            })
            .toList();
        if (toProcess.isEmpty()) {
            log.info("All {} embeddings up-to-date for user {}; skipping", all.size(), user.getSpotifyId());
            return;
        }
        int embedded = embedAndUpsert(user.getId(), toProcess);
        log.info("Updated {}/{} track embeddings for user {}", embedded, all.size(), user.getSpotifyId());
    }

    /**
     * Returns the {@code k} library tracks semantically nearest to {@code query} for the given
     * user, ordered by cosine similarity (closest first).
     *
     * <p>Returns an empty list when RAG is disabled, the user has no embeddings, or any step
     * fails — callers should fall back to top-tracks context when the list is empty.
     *
     * @param userId the app-local user ID (from {@code app_user.id})
     * @param query  the raw user request text to embed
     * @param k      maximum number of tracks to return
     * @return ordered list of track text strings, or empty on failure
     */
    public List<String> findRelevantTracks(Long userId, String query, int k) {
        if (!ragEnabled || userId == null) return List.of();
        try {
            EmbeddingResponse resp = embeddingModel.call(
                new EmbeddingRequest(List.of(query), OpenAiEmbeddingOptions.builder().build()));
            if (resp.getResults().isEmpty()) return List.of();
            float[] queryVector = resp.getResults().get(0).getOutput();
            return jdbcTemplate.query(
                "SELECT track_text FROM track_embedding " +
                "WHERE user_id = ? " +
                "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, rowNum) -> rs.getString("track_text"),
                userId, toVectorLiteral(queryVector), k
            );
        } catch (RuntimeException e) {
            log.warn("RAG retrieval failed for user {}; falling back to top-tracks", userId, e);
            return List.of();
        }
    }

    /**
     * Returns {@code true} if the user has at least one embedded track in {@code track_embedding}.
     * Used by {@link com.spotify.recommender.config.OAuth2SuccessHandler} to decide whether to
     * trigger a library build for returning users who pre-date the RAG feature.
     *
     * @param userId the app-local user ID; returns {@code false} immediately if null
     * @return true if at least one row exists for this user
     */
    public boolean hasEmbeddings(Long userId) {
        if (userId == null) return false;
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM track_embedding WHERE user_id = ?",
                Integer.class, userId);
            return count != null && count > 0;
        } catch (RuntimeException e) {
            log.warn("Failed to check embedding count for user {}; assuming none", userId, e);
            return false;
        }
    }

    /**
     * Nightly refresh at 03:30 — calls {@link #updateLibrary} for every user in the DB.
     * Failures for individual users are caught and logged; the loop continues to the next user.
     *
     * <p>RAG embedding pipeline disabled pending privacy review.
     * Sending users' full Spotify libraries to OpenAI API requires explicit user consent
     * and privacy policy coverage before enabling.
     * See FEATURES-TODO.md and privacy discussion.
     * The infrastructure (EmbeddingService, schema, pgvector) remains in place for when
     * this is properly addressed.
     */
    // RAG DISABLED — privacy review pending
    // @Scheduled(cron = "0 30 3 * * *")
    public void refreshAllEmbeddings() {
        if (!ragEnabled) return;
        List<AppUser> users = userRepository.findAll();
        int success = 0;
        for (AppUser user : users) {
            try {
                updateLibrary(user);
                success++;
            } catch (Exception e) {
                log.warn("Unexpected error refreshing embeddings for user {}", user.getSpotifyId(), e);
            }
        }
        log.info("Nightly embedding refresh complete: {}/{} users updated", success, users.size());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Collects track/playlist data from every non-Fractals playlist in the user's library.
     */
    private void fetchAllPlaylistTracks(String accessToken, String spotifyId,
            Map<String, TrackDto> trackById, Map<String, Set<String>> trackToPlaylists) {
        int offset = 0;
        final int PAGE = 50;
        while (true) {
            SpotifyPage<PlaylistDto> page;
            try {
                page = spotifyApi.getPlaylistsWithToken(accessToken, spotifyId, offset, PAGE);
            } catch (RuntimeException e) {
                log.warn("Failed to fetch playlists (offset={}) for user {}; stopping pagination",
                    offset, spotifyId, e);
                break;
            }
            if (page == null || page.getItems() == null) break;
            for (PlaylistDto playlist : page.getItems()) {
                if (playlist.getId() == null || playlist.getName() == null) continue;
                // Skip Fractals-owned playlists — they contain tracks the user already knows
                // and would dilute the semantic signal with already-recommended content.
                if (playlist.getName().toLowerCase().startsWith("fractals-")) continue;
                fetchPlaylistTracksInto(accessToken, spotifyId, playlist, trackById, trackToPlaylists);
            }
            // Advance by the full requested page size, not the filtered item count — the owner
            // filter in getPlaylistsWithToken() can return fewer items than PAGE on a non-last page.
            offset += PAGE;
            // Use Spotify's next-cursor rather than item count to detect the final page.
            if (page.getNext() == null) break;
        }
    }

    /** Paginates a single playlist and merges its tracks into the shared library maps. */
    private void fetchPlaylistTracksInto(String accessToken, String spotifyId, PlaylistDto playlist,
            Map<String, TrackDto> trackById, Map<String, Set<String>> trackToPlaylists) {
        int offset = 0;
        final int PAGE = 100;
        while (true) {
            SpotifyPage<PlaylistTrackDto> page;
            try {
                page = spotifyApi.getPlaylistTracksWithToken(accessToken, playlist.getId(), offset, PAGE);
            } catch (RuntimeException e) {
                log.warn("Failed to fetch tracks from '{}' (offset={}) for user {}; skipping",
                    playlist.getName(), offset, spotifyId, e);
                break;
            }
            if (page == null || page.getItems() == null || page.getItems().isEmpty()) break;
            for (PlaylistTrackDto pt : page.getItems()) {
                TrackDto t = pt.getTrack();
                if (t == null || t.getId() == null) continue;
                trackById.putIfAbsent(t.getId(), t);
                trackToPlaylists.computeIfAbsent(t.getId(), k -> new LinkedHashSet<>())
                                .add(playlist.getName());
            }
            int fetched = page.getItems().size();
            offset += fetched;
            if (fetched < PAGE) break;
        }
    }

    /** Appends top-50 tracks from all three time ranges, deduplicating against playlist tracks. */
    private void addTopTracks(String accessToken, String spotifyId,
            Map<String, TrackDto> trackById, Map<String, Set<String>> trackToPlaylists) {
        for (String timeRange : List.of("short_term", "medium_term", "long_term")) {
            try {
                SpotifyPage<TrackDto> page = spotifyApi.getTopTracksWithToken(accessToken, timeRange, 50);
                if (page == null || page.getItems() == null) continue;
                for (TrackDto t : page.getItems()) {
                    if (t.getId() == null) continue;
                    trackById.putIfAbsent(t.getId(), t);
                    trackToPlaylists.computeIfAbsent(t.getId(), k -> new LinkedHashSet<>());
                }
            } catch (RuntimeException e) {
                log.warn("Failed to fetch top tracks ({}) for user {}; continuing", timeRange, spotifyId, e);
            }
        }
    }

    /**
     * Converts the deduplicated track map into a list of {@link LibraryTrack} records, building
     * the human-readable text string used as the embedding input.
     */
    private List<LibraryTrack> buildLibraryTracks(Map<String, TrackDto> trackById,
            Map<String, Set<String>> trackToPlaylists) {
        List<LibraryTrack> result = new ArrayList<>();
        for (Map.Entry<String, TrackDto> entry : trackById.entrySet()) {
            TrackDto t = entry.getValue();
            if (t.getName() == null) continue;
            String artists = t.getArtists() == null ? "" : t.getArtists().stream()
                .map(TrackDto.ArtistRef::getName)
                .filter(n -> n != null)
                .collect(Collectors.joining(", "));
            String text = t.getName() + " by " + artists;
            if (t.getAlbum() != null && t.getAlbum().getName() != null) {
                text += ", from " + t.getAlbum().getName();
            }
            Set<String> playlists = trackToPlaylists.getOrDefault(t.getId(), Set.of());
            if (!playlists.isEmpty()) {
                text += ", in playlist: " + String.join(", ", playlists);
            }
            String playlistNames = playlists.isEmpty() ? null : String.join(", ", playlists);
            result.add(new LibraryTrack(t.getId(), text, playlistNames));
        }
        return result;
    }

    /**
     * Calls the embedding model in batches and upserts results into {@code track_embedding}.
     * Batch failures are isolated: a failed batch is logged and skipped; subsequent batches continue.
     *
     * @return the total number of tracks successfully embedded and upserted
     */
    private int embedAndUpsert(long userId, List<LibraryTrack> tracks) {
        int embedded = 0;
        int totalBatches = (tracks.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < tracks.size(); i += batchSize) {
            List<LibraryTrack> batch = tracks.subList(i, Math.min(i + batchSize, tracks.size()));
            int batchNum = i / batchSize + 1;
            try {
                List<String> texts = batch.stream().map(LibraryTrack::trackText).toList();
                EmbeddingResponse resp = embeddingModel.call(
                    new EmbeddingRequest(texts, OpenAiEmbeddingOptions.builder().build()));
                List<Embedding> embeddings = resp.getResults();
                if (embeddings.size() != batch.size()) {
                    log.warn("Batch {}/{}: expected {} embeddings, got {}; skipping",
                        batchNum, totalBatches, batch.size(), embeddings.size());
                    continue;
                }
                final List<LibraryTrack> finalBatch = batch;
                final List<Embedding> finalEmbeddings = embeddings;
                jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int j) throws SQLException {
                        LibraryTrack lt = finalBatch.get(j);
                        ps.setLong(1, userId);
                        ps.setString(2, lt.spotifyTrackId());
                        ps.setString(3, lt.trackText());
                        ps.setString(4, toVectorLiteral(finalEmbeddings.get(j).getOutput()));
                        ps.setString(5, lt.playlistNames());
                    }
                    @Override
                    public int getBatchSize() { return finalBatch.size(); }
                });
                embedded += batch.size();
            } catch (RuntimeException e) {
                log.warn("Embedding batch {}/{} failed for user {}; skipping {} tracks",
                    batchNum, totalBatches, userId, batch.size(), e);
            }
        }
        return embedded;
    }

    private Map<String, Instant> loadExistingTimestamps(long userId) {
        Map<String, Instant> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT spotify_track_id, last_updated FROM track_embedding WHERE user_id = ?",
            rs -> {
                result.put(rs.getString("spotify_track_id"),
                           rs.getTimestamp("last_updated").toInstant());
            },
            userId
        );
        return result;
    }

    /**
     * Converts a float array to the pgvector literal format {@code [0.1,0.2,...]} expected by
     * {@code CAST(? AS vector)}. pgvector does not accept Java array types directly via JDBC.
     */
    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Immutable record holding the data needed to embed and upsert a single library track. */
    private record LibraryTrack(String spotifyTrackId, String trackText, String playlistNames) {}
}
