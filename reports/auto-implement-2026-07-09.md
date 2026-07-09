# Auto-Implementer Run — 2026-07-09

## Review report acted on
reports/2026-07-09-1312-review.md

## Findings acted on

### HIGH (original)
1. **PlaceholderService — missing system prompt.** Added `SYSTEM_PROMPT` and
   `.system(...)` on the Anthropic call. (`service/PlaceholderService.java`)
2. **MCP read-only invariant broken by auto-save.** Added a
   `recommend(request, persistToPlaylist)` overload; auto-save and the
   playlist-creating snapshot fetch now run only on the REST path. MCP
   `get_recommendations` and `GET /quick` call with `persistToPlaylist=false`.
   Removed the TEMPORARY auto-save comment.
   (`service/RecommendationService.java`, `mcp/SpotifyMcpTools.java`,
   `controller/RecommendationController.java`)
4. **Untested changed code (HIGH per review policy).** Added `UserServiceTest`,
   `PlaylistServiceTest`, `AuthControllerTest`, `UserControllerTest`,
   `PlaylistControllerTest`.

### Escalated MEDIUM/LOW → HIGH (Engineering Principles)
- **`/quick` GET side effect** [Defence in depth] — GET no longer auto-saves;
  state changes must not be reachable via a CSRF-exempt GET.
- **RecommendationService.recommend getTopTracks NPE** [Fail safe] — null-guarded
  the top-tracks page.
- **SpotifyApiService.getOrCreatePlaylist null derefs** [Fail safe] — fail-closed
  guards on the playlist page, profile/user-ID, and created-playlist body.
- **UserController timeRange unvalidated** [Fail safe] — `@Pattern` constraint
  added; plus `GlobalExceptionHandler` now maps `ConstraintViolationException`
  to 400 so the `@Validated` `@RequestParam` constraints fail safe (were 500s).

### Skipped — not a principles violation (left for human review)
- Missing Javadoc on `AppUser`, `PlaylistController`, `PlaylistNameUtil`, etc.
  (quality convention, not an Engineering Principle).
- Daily placeholder job has no enable flag (cost/design, not a security
  principle).
- Various LOW correctness/log-hygiene items (see review report).

### Deferred — protected files (human implementation required)
- **`config/SecurityConfig.java`** (HIGH-3) — H2 console CSRF-exempt + permitAll.
  Contradicts the read-only-only CSRF-exemption principle.
- **`config/WebClientConfig.java`** (MEDIUM→HIGH) — unguarded Retry-After parse.
  [Fail safe]
- **`.github/workflows/auto-implement.yml`** (HIGH-5 + MEDIUM→HIGH) — broad
  `git push *` and wildcard tool grants. [Least privilege]
- **`.github/workflows/code-review.yml`** (MEDIUM→HIGH) — wildcard git commit,
  raw `git push origin devel`, no guaranteed report on early failure.
  [Least privilege / Auditability]

## Branch created
fix/auto-2026-07-09

## PR opened
See PR link in the run output (or PR_URL fallback if `gh` lacked permission).

## Tests
mvn test: 148/148 passing (0 failures, 0 errors, 4 skipped — RAG disabled)
