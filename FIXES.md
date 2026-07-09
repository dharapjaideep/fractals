# Fix Log

## 2026-07-09 — Auto-implement HIGH findings from 2026-07-09-1312 review

Automated fixes by the Auto-Implementer Agent acting on
`reports/2026-07-09-1312-review.md`. Branch: `fix/auto-2026-07-09`.

### HIGH findings acted on

- **`service/PlaceholderService.java`** — Added a mandatory `SYSTEM_PROMPT`
  and `.system(SYSTEM_PROMPT)` to the Anthropic `MessageCreateParams` builder.
  Every LLM call must include a system prompt (CLAUDE.md); the prompt also
  guards against injection via user track/playlist names. (Finding HIGH-1.)

- **`service/RecommendationService.java`, `mcp/SpotifyMcpTools.java`,
  `controller/RecommendationController.java`** — Restored the MCP read-only
  invariant. Added a `recommend(request, persistToPlaylist)` overload; the
  auto-save side effect (and the playlist-creating snapshot fetch) now run only
  when `persistToPlaylist` is true. The MCP `get_recommendations` tool and the
  read-only `GET /api/recommendations/quick` endpoint now call with
  `persistToPlaylist=false`, so nothing reachable via the CSRF-exempt `/mcp/**`
  path (or a CSRF-exempt GET) can write to the user's Spotify account. Removed
  the TEMPORARY "must be removed before deployment" auto-save comment.
  (Finding HIGH-2, and escalated MEDIUM on `/quick` GET side effect.)

- **Test coverage gap (HIGH per review policy)** — Added tests:
  `UserServiceTest` (fail-closed 404, both branches), `PlaylistServiceTest`
  (delegation), `AuthControllerTest` (401 branch + OAuth2 DTO mapping),
  `UserControllerTest` (delegation, timeRange default, validation),
  `PlaylistControllerTest` (delegation, pagination constraints).
  (Finding HIGH-4.)

### MEDIUM/LOW escalated to HIGH (Engineering Principles) and acted on

- **`controller/RecommendationController.java`** — `/quick` GET no longer
  auto-saves (see above). [Defence in depth — state change must not be reachable
  via a CSRF-exempt GET.]

- **`service/RecommendationService.java`** — `getTopTracks(...).getItems()` is
  now null-guarded; a null page degrades to an empty result instead of an NPE.
  [Fail safe.]

- **`service/SpotifyApiService.java`** — `getOrCreatePlaylist` now guards the
  playlist page, the user profile/ID, and the created-playlist body, failing
  closed with an explicit `IllegalStateException` instead of dereferencing null.
  [Fail safe.]

- **`controller/UserController.java`** — `timeRange` is constrained with
  `@Pattern` to `short_term|medium_term|long_term`; added missing class/method
  Javadoc while editing. [Fail safe — reject bad input locally.]

- **`exception/GlobalExceptionHandler.java`** — Added a
  `ConstraintViolationException` → HTTP 400 handler. Required so the `@Validated`
  `@RequestParam` constraints (the new `@Pattern` and the pre-existing
  `@Min`/`@Max` on the user/playlist controllers) fail safe as 400s rather than
  surfacing as 500s. [Fail safe.]

### Deferred — require human review (protected files / workflow scope)

- **`config/SecurityConfig.java`** (HIGH-3) — H2 console CSRF-exempt +
  `permitAll`. Protected file; not modified. [Contradicts the read-only-only
  CSRF-exemption principle.]
- **`config/WebClientConfig.java`** (MEDIUM, escalated) — unguarded
  `Long.parseLong(Retry-After)`. Protected file; not modified. [Fail safe.]
- **`.github/workflows/auto-implement.yml`** (HIGH-5 + MEDIUM) — broad
  `Bash(git push *)` and other wildcard tool grants. Agent cannot modify
  `.github/workflows/`. [Least privilege.]
- **`.github/workflows/code-review.yml`** (MEDIUM, escalated) — wildcard
  `Bash(git commit *)`, raw `git push origin devel`, no guaranteed report on
  early failure. Agent cannot modify `.github/workflows/`. [Least privilege /
  Auditability.]

### Tests

`mvn test`: 148 run, 0 failures, 0 errors, 4 skipped (RAG-disabled).
