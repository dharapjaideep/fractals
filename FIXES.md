# Fix Log

## 2026-07-09 — Fix HIGH findings H1, H2, H5 from initial code review

- `src/main/java/com/spotify/recommender/service/PlaceholderService.java` — added a `.system(...)` prompt to the `MessageCreateParams` builder in `refreshPlaceholder`/`regenerateAsync`. The call previously had no system prompt, violating CLAUDE.md's mandatory system-prompt principle (HIGH finding H1 in `reports/2026-07-09-1312-review.md`).
- `src/main/java/com/spotify/recommender/service/RecommendationService.java` — removed the TEMPORARY auto-save block from `recommend()` (previously L129–141). The block called `saveToPlaylist(...)`, making the read-only-assumed `get_recommendations` MCP tool perform a write reachable via the CSRF-exempt `/mcp/**` endpoint (HIGH finding H2). The in-code comment itself said this "must be removed before any deployment." `ChatService` already handles auto-save for the `/api/chat` flow, so no replacement is needed. The now-unused-from-this-path `saveToPlaylist(List<String>, String, PlaylistSnapshot)` method is kept — it remains directly unit-tested in `RecommendationServiceTest`.
- `.github/workflows/auto-implement.yml` — scoped `Bash(git push *)` down to `Bash(git push -u origin fix/*)` (HIGH finding H5). The prior broad wildcard let the write-scoped Auto-Implementer push to any ref, not just `fix/*` branches. A previous attempt to scope this to `Bash(git push origin fix/*)` (no `-u`) had been reverted because the agent's actual invocation includes `-u`, which didn't match that pattern — the new pattern matches the real invocation.

Verified with `mvn test` — 126 tests, 0 failures, 0 errors, BUILD SUCCESS.

## 2026-07-09 — H4 noted as planned follow-up (not yet fixed)

HIGH finding H4 from `reports/2026-07-09-1312-review.md` (test coverage gaps) is not
addressed by this fix pass. Missing tests, to be added later:
- `AuthController`
- `UserController`
- `PlaylistController`
- `UserService`
- `PlaylistService`

## 2026-07-09 — Fix HIGH finding H3: remove H2 console from SecurityConfig.java

- `src/main/java/com/spotify/recommender/config/SecurityConfig.java` *(protected file — human-reviewed and approved before writing)* — removed `/h2-console/**` from the CSRF `ignoringRequestMatchers(...)` exemption and from `permitAll()`, and removed the `frameOptions().sameOrigin()` override that existed solely to allow H2 console iframes (HIGH finding H3). Confirmed dead code: no `application*.yml` sets `spring.h2.console.enabled`, so Spring Boot's `H2ConsoleAutoConfiguration` never activates and the H2 console servlet is never registered — these matchers were exempting/permitting a path that was never actually served, creating an unnecessary security surface. `com.h2database:h2` remains a `runtime`-scope dependency in `pom.xml` (unchanged, out of scope for this fix). No test referenced H2 console or `frameOptions`.

Verified with `mvn test` — 126 tests, 0 failures, 0 errors, BUILD SUCCESS.

## 2026-07-10 — Remove stray review-log link from README

- `README.md` — removed a stray `[review-log.md](reports/review-log.md)` line left in the "How Fractals works" section, and restored the blank line before the `## How it works` heading that the stray line had collapsed. Docs-only change, no code affected.

## 2026-07-10 — Fix HIGH finding H4: add missing tests for AuthController, UserController, PlaylistController, UserService, PlaylistService

- `src/test/java/com/spotify/recommender/controller/AuthControllerTest.java` (new) — covers the `/api/auth/me` 401-vs-authenticated branch (a non-`OAuth2AuthenticationToken` authenticated principal, e.g. `@WithMockUser`, now hits the controller's own 401 branch), the unauthenticated redirect, and `UserProfileDto` mapping on the success path using a constructed `OAuth2AuthenticationToken`.
- `src/test/java/com/spotify/recommender/controller/UserControllerTest.java` (new) — covers `/api/user/profile`, `/api/user/top-tracks`, `/api/user/top-artists`: `timeRange` default (`medium_term`), `limit` default (20) and pass-through, and `@Min(1)`/`@Max(50)` validation.
- `src/test/java/com/spotify/recommender/controller/PlaylistControllerTest.java` (new) — covers `/api/playlists` and `/api/playlists/{id}/tracks`: `offset`/`limit` defaults and pass-through, `@Min`/`@Max` validation, and delegation of the path-variable playlist ID to `PlaylistService`.
- `src/test/java/com/spotify/recommender/service/UserServiceTest.java` (new) — covers `findBySpotifyId` on both branches: existing user returned, and the fail-closed `404 ResponseStatusException` (security-relevant path noted in H4) when no user matches.
- `src/test/java/com/spotify/recommender/service/PlaylistServiceTest.java` (new) — covers argument/return-value pass-through delegation to `SpotifyApiService.getPlaylists`/`getPlaylistTracks`.

While writing the `UserController`/`PlaylistController` validation tests, found that `@Min`/`@Max` violations on `@RequestParam`s were **not** actually producing 400 responses: Spring's AOP-based `MethodValidationInterceptor` throws `jakarta.validation.ConstraintViolationException` directly (a different mechanism than `@Valid @RequestBody`'s `MethodArgumentNotValidException`), and `GlobalExceptionHandler` had no handler for it — so invalid input like `?limit=999` was returning an uncaught 500 in production, not a 400. Fixed as part of this pass (confirmed with the user before making the change, since it's a behavior change beyond the original test-only scope):
- `src/main/java/com/spotify/recommender/exception/GlobalExceptionHandler.java` — added `@ExceptionHandler(ConstraintViolationException.class)` returning 400 with `{"error": "invalid_request", "message": ...}`.
- `src/test/java/com/spotify/recommender/exception/GlobalExceptionTestController.java` / `GlobalExceptionHandlerTest.java` — added a `@Min(1)` test endpoint and a test asserting the handler now returns 400 instead of 500.

Verified with `mvn test` — 149 tests, 0 failures, 0 errors, BUILD SUCCESS.

## 2026-07-10 — Address MEDIUM finding from 2026-07-10 review: lock in the H2 auto-save removal with a regression test

- `src/test/java/com/spotify/recommender/service/RecommendationServiceTest.java` — added `verify(spotifyApi, never()).addTracksToPlaylist(anyString(), anyList());` to the `recommend()` happy-path test (`recommend_resolvesValidJsonArray_intoRankedTracks`). Per `reports/2026-07-10-1228-review.md`, the success-path test previously asserted the returned `RecommendationResponse` but never asserted the absence of the playlist write, so a future re-introduction of auto-save into `recommend()` — the exact regression fixed for HIGH finding H2 — would not have been caught by the suite.

Verified with `mvn test` — 149 tests, 0 failures, 0 errors, BUILD SUCCESS.
