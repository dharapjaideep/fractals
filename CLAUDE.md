# Fractals — CLAUDE.md

## What this project is

Spring Boot 3.4 / Java 21 REST service that
authenticates users via Spotify OAuth2 and returns
personalized music recommendations powered by
Anthropic's Claude API.

The recommendation engine uses a two-step approach:
1. Fetch the user's top tracks and playlist context
   from Spotify as taste signals
2. Construct a prompt and pass that context to Claude,
   which identifies the emotional thread connecting
   the user's music and discovers unexpected tracks
   that share that quality across genres and cultures

An MCP (Model Context Protocol) server exposes
read-only Spotify tools to Claude Code for
development-time queries.

## Tech stack
- Java 21, Spring Boot 3.4.1, Maven
- Spring Security OAuth2 Client (Authorization Code Flow)
- WebClient (WebFlux) inside a servlet (MVC) app —
  always .block() at the service layer
- Spring Data JPA + JdbcOAuth2AuthorizedClientService
  (Postgres in dev via Docker, Postgres in prod)
- Anthropic Java SDK — Claude API for recommendations
- Spring AI — MCP server exposing Spotify tools to Claude
- OpenAI API — text-embedding-3-small for semantic
  embeddings (infrastructure built, currently disabled
  pending privacy review decision)
- pgvector — Postgres extension for vector similarity
  search (infrastructure built, currently disabled
  pending privacy review decision)

## Running tests
```bash
mvn test
```
Tests are self-contained — no external service
credentials required (Spotify, Anthropic, OpenAI):

- MockWebServer for all Spotify API calls
- Mockito for the service layer
- @WebMvcTest slices for controller tests
- Reflection test enforcing MCP tool read-only surface

## Key design decisions

- **Tokens never in AppUser** — JdbcOAuth2AuthorizedClientService
owns token storage; AppUser holds only identity fields.

- **All Spotify calls .block() at the service layer** — WebClient
(reactive) is used for its OAuth2 integration, but blocking
at the service boundary keeps the rest of the app
straightforward servlet-style code.

- **MCP tools are read-only** — enforced by a reflection-based
test that fails the build if any MCP tool is not read-only.
This allows /mcp/** to be exempt from CSRF protection safely —
read-only operations cannot cause state changes even if
triggered cross-site.

- **Auto-save to Spotify playlist** — after receiving
recommendations, tracks are automatically saved to a Fractals
playlist in the user's Spotify account. Deliberate UX decision
to reduce friction (no manual copying needed). Implemented in
the REST service layer only, not exposed via MCP — therefore
CSRF-protected like other state-changing endpoints. Future
option to be explored: give users more control over when and
whether to save.

- **Two-step emotional reasoning** — Claude is prompted to first
identify the emotional thread connecting the user's tracks,
then find music from unexpected directions that shares that
quality. This produces cross-genre discovery rather than
genre categorisation.

- **RAG pipeline built, currently disabled** — EmbeddingService
and pgvector infrastructure exist for semantic retrieval over
user music libraries. Disabled pending a privacy review of
sending user library data to OpenAI.

## Engineering Principles

These principles guide development throughout 
the project. However, the Review Agent audits 
every commit against them automatically, flagging 
findings by severity; HIGH findings trigger the 
Auto-Implementer, which opens a pull request for 
human review before any fix is merged.

### Least privilege
Every process, Agent, workflow, and service account must have 
only the minimum permissions needed to do its job. Nothing more.

Apply this to:
- GitHub Actions workflows: scope --allowedTools explicitly, 
  never use broad Bash access when specific commands suffice,
  scope file write permissions to specific directories
- Spring Security: never grant broader access than the specific 
  endpoint or role requires
- API scopes: request only the Spotify scopes the app actually uses
- Database access: controllers never touch repositories directly,
  services own data access
- Agent permissions: read-only Agents get no write tools,
  write-scoped Agents are limited to specific paths

Every workflow, configuration, or infrastructure 
change documents what permissions are granted 
and why, and flags any permission that could 
be narrowed.

### Defence in depth
Never rely on a single layer of protection. Apply at least two 
independent controls for any sensitive operation:
- Prompt-level instruction AND CLI permission flag
- Security config AND input validation
- Environment variable AND secret manager

### Fail safe
When uncertain, fail closed rather than open. An explicit error 
is always preferable to silent degradation or unexpected access.

### Auditability
Every automated change must be traceable:
- Automated commits must identify the Agent that made them
- FIXES.md must be updated for every source code change
- Workflow runs must produce a report even if findings are empty

### System prompts required
- Every LLM API call (Anthropic or OpenAI) must 
  include a system prompt. A call without a system 
  prompt is a HIGH finding — it means there are no 
  guardrails on what the model can be instructed to do.

## Conventions
- **Fix log**: whenever code fixes are applied, append a dated entry to `FIXES.md` in the project root using the format `## YYYY-MM-DD — <short description>`, listing each file changed and the reason.

## Code commenting conventions
- Every class must have a Javadoc comment explaining its purpose and responsibility.
- Every public method must have a Javadoc comment explaining what it does, its parameters, and what it returns.
- Complex or non-obvious logic must have inline comments explaining **WHY**, not just what.
- Security-relevant code must always have a comment explaining the security decision (e.g. why something is exempt from CSRF, why a scope is required, etc.).
- **TEMPORARY** code must always have a comment explaining what it is and when to remove it.

# Agent Instructions

When running as an agent on this project:

### Before making any changes
- Run `mvn compile` first to confirm the project builds cleanly
- Report any compile errors before proceeding

### Architecture rules
- All Spotify API calls must go through SpotifyApiService — never call the Spotify API directly from a controller or other service
- All business logic must live in the service layer — controllers are thin delegators only
- Never add token handling to AppUser or any entity class — tokens belong in oauth2_authorized_client only

### Files that require human review before changes
- SecurityConfig.java — any changes here affect all authentication and authorization
- WebClientConfig.java — changes here affect token injection for every Spotify API call
- schema.sql — changes here affect the database schema for all environments

### After making changes
- Run `mvn test` and report the full results before finishing
- If any tests fail, attempt to fix them before reporting done
- Append an entry to FIXES.md describing what was changed and why

### Handling uncertainty and assumptions

Before starting any task, if any of the following are unclear, STOP and ask:
- Which files or classes should be modified
- What the expected behaviour should be (inputs, outputs, edge cases)
- Whether a new file/class is needed or an existing one should be extended
- Any data model changes (new fields, new tables)

Do NOT assume and proceed. List your questions as a numbered list and wait
for answers before writing any code.

When you do make a necessary assumption (minor style choices etc.),
state it explicitly: "I'm assuming X — let me know if that's wrong."

### When you don't know how to proceed

If you encounter any of the following, STOP and report it rather than guessing:

- A library, framework, or API you haven't seen before in this codebase
- A requirement that conflicts with an existing design decision
- A task that would require changing a protected file (SecurityConfig.java,
  WebClientConfig.java, schema.sql)
- A situation where two reasonable approaches exist and the choice
  significantly affects the architecture

Report it like this:
"I'm not sure how to proceed here because [reason].
The options I can see are: [option A] or [option B].
Which would you prefer, or would you like to discuss?"

## Personal Preferences
Read PERSONAL-PREFERENCES.md for personal workflow conventions.
