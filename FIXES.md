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
