# Phase 5 — Grounded chat and session context

Phase 6 now extends this baseline with native cart tools and v2 prompts. See
[Phase 6](phase-6.md) for current cart behavior, action results and atomic completion.
The Phase 5 implementation and verification notes below describe its original scope.

## Run

Start **AI Cart - Backend (dev)** in IntelliJ as usual, or:

```powershell
.\mvnw.cmd clean package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

The existing development `.env` supplies database and Google credentials. No new secret is required. Production uses `prod` and the existing platform environment variables. Flyway applies additive migration **V6** for chat requests/messages; existing menu, vectors, sessions and carts are preserved.

The published Phase 3 knowledge and Phase 4 embedding configuration must agree. There is no need to re-ingest unchanged knowledge merely to enable chat.

## Frontend contract

Users type ordinary English. The frontend sends **only a fresh UUID and the message**, with the existing session header. The backend chooses MENU, GUIDE or BOTH and constructs validated filters. The developer retrieval endpoint remains a Phase 4 inspection interface.

```http
POST /api/v1/chat
Content-Type: application/json
X-Session-Id: SESSION_<existing UUID>

{
  "requestId": "ab2ead46-b921-4dfe-bdbb-04903e080535",
  "message": "Suggest mild vegetarian mains under 300 rupees."
}
```

Use a new request ID for a new message; reuse the **same ID and identical text** when retrying that message. Unknown fields, malformed UUIDs and coercions are rejected. Messages are trimmed, with a 1–2000 character limit.

The global envelope remains `{timestamp,data,error}`. Successful `data` contains:

| Field | Meaning |
|---|---|
| requestId | Client UUID |
| assistantMessageId | Persisted numeric transcript ID |
| outcome | Machine-readable result category |
| reply | Ready-to-display answer with server-rendered menu facts |
| sections | Text sections and supporting source IDs; fixed notices may be uncited |
| recommendations | Up to five database-built cards, in display/reference order |
| sources | Retrieved source IDs, titles, type, full content and snippet |
| cart | Authoritative cart snapshot taken during this request |
| timings | Planning, knowledge, and total elapsed milliseconds |
| diagnostics | Intent, target, filters, source revision where applicable, model/embedding calls, token usage and prompt version |

Recommendations contain `id, name, description, category, ingredients, dietaryTags, spiceLevel, price, currency, available`. Prices and spice values are taken from PostgreSQL. Display cards in returned order so “the second one” refers to the same item.

Outcomes include `ANSWERED`, `PARTIAL_EVIDENCE`, `INFORMATION_MISSING`, `NO_EVIDENCE`, `NO_ELIGIBLE_ITEMS`, `CLARIFICATION`, `RESTAURANT_CONFIRMATION_REQUIRED`, `GREETING`, `OUT_OF_SCOPE`, `CART`, and `CART_ACTION_UNAVAILABLE`.

### History

```http
GET /api/v1/chat/messages?limit=50
X-Session-Id: SESSION_<existing UUID>

GET /api/v1/chat/messages?limit=50&before=123
X-Session-Id: SESSION_<existing UUID>
```

`data.messages` is chronological, with `id,requestId,role,content,createdAt,requestStatus,metadata`. Default page size 50, valid range 1–100. For older messages use the returned `nextBefore`; null means no older page. Failed/in-progress user messages stay visible with their status but are excluded from subsequent model conversation history.

### PowerShell example

```powershell
$api = 'http://localhost:8080/api/v1'
$session = (Invoke-RestMethod -Method Post "$api/sessions").data
$headers = @{ 'X-Session-Id' = $session.sessionId }
$body = @{
    requestId = [guid]::NewGuid().ToString()
    message = 'Suggest mild vegetarian main dishes under 300 rupees.'
} | ConvertTo-Json
$result = Invoke-RestMethod -Method Post "$api/chat" -Headers $headers -ContentType 'application/json' -Body $body
$result.data

# Reusing the same body replays the stored response without calling Gemini again.
Invoke-RestMethod -Method Post "$api/chat" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$api/chat/messages?limit=20" -Headers $headers

# End only this test session when finished.
Invoke-RestMethod -Method Delete "$api/sessions/current" -Headers $headers
```

Follow-up examples (new request ID each time, same session):

- “Tell me the ingredients of the second one.”
- “Compare those dishes.”
- “Keep it vegetarian but show starters instead.”
- “Remove the price limit.”
- “Can I request less chilli?”
- “Suggest vegan mains and explain takeaway packaging.”
- “What are your opening hours?” — demo knowledge explicitly does not provide them.
- “I have a peanut allergy. Which dishes are safe?” — no safety guarantees or recommendation cards.
- “Show my cart.” — reads current cart.
- “Add two of the first dish.” — directs users to existing cart controls; Phase 6 adds tools.
- “Who won the football world cup?” — restaurant scope response.

## Processing

1. **Claim a turn:** validate session/message, lock the session row briefly, register a ChatRequest and one USER transcript message. Load typed working context, ten completed messages and a compact current catalog.
2. **Plan:** a private Gemini client produces native schema-constrained JSON using versioned `prompts/chat/v1/planner.*`. It chooses intent, target, branch queries, preference deltas and references.
3. **Validate:** Java checks enums, allowlisted tags/categories, numeric limits and item IDs. Inherited preferences remain until explicitly changed/cleared. “Under 300” is INR 299.99; “up to” is inclusive. Spice: non-spicy 0; mild 0–1; medium 2; spicy 3–5; very spicy 4–5; extra hot 5. Whole-order/group budgets and unsupported hard requirements require clarification.
4. **Retrieve:** normal queries reuse Phase 4 filters and semantic retrieval. BOTH searches menu and guide independently. Named/previously recommended items use a bounded internal exact-ID lookup of current menu facts and linked source chunks, with index integrity checks and no embedding rediscovery. Missing/retired references never silently become different items.
5. **Answer:** versioned `answer.*` supplies full evidence plus current facts. The model must acknowledge missing facts. Java validates citations and eligible, unique recommendation IDs. Server-rendered fact lines/comparisons use database prices, spice and availability. Numeric menu claims are excluded from model prose.
6. **Complete:** revalidate active session, attempt token and lease; atomically save ASSISTANT, response snapshot and context. No database transaction stays open during model/embedding calls.

Greeting, unrelated questions, clarification and cart intents skip knowledge/answer generation. Mixed unrelated questions retain the supported restaurant part and receive a scope notice. Missing menu/guide branches are explicitly reported. Allergen questions retrieve the policy caveats and require restaurant confirmation; menu tags do not certify allergy safety.

GUIDE turns preserve food preferences and recommendation ordering. New recommendation turns replace ordered IDs, clearing them when there are no cards. Failed and unrelated turns do not overwrite working context. Clarification stores a pending question for the next turn. The restaurant-confirmation requirement persists separately from food filters, so an allergy concern is not forgotten when older messages leave the prompt; it clears only when explicitly withdrawn.

## Reliability and limits

| Situation | Result |
|---|---|
| Same session + same ID + same completed text | Original saved response; no model call |
| Same ID with changed text | 409 REQUEST_ID_CONFLICT |
| Same request still active | 409 CHAT_REQUEST_IN_PROGRESS |
| Another active request in that session | 409 CHAT_BUSY |
| Retry a failed request before any newer turn | New attempt, one original USER message |
| Retry an old failed turn after newer requests | 409 CHAT_RETRY_STALE; use a new ID |
| Worker exceeded its five-minute lease | Next request can recover; late token cannot finish/fail replacement |
| Session expired/deleted while provider runs | No late assistant/context write |
| Provider or repeated output validation failure | Safe 503 code; message remains FAILED and retryable |

There is one active-request partial unique index per session, a unique request ledger key and a unique transcript role per request. Session deletion cascades both tables. Failed attempts never replace a completed response.

Replayed responses include the **original cart snapshot**. Fetch `GET /api/v1/cart` for current state. Availability can change after a response; the existing cart API still validates updates.

Each model stage has a 30-second HTTP call limit with SDK/Spring transport retries disabled. There are normally two chat calls, with **at most one output repair shared across both stages**. The existing embedding retry policy remains separate. Input is estimated with CL100K and capped around 12,000 tokens; old complete conversation pairs are dropped first. Evidence is never chopped to fit. Token estimates are not exact Gemini token counts; actual provider usage is recorded.

Source IDs and structured constraints are checked mechanically. These checks cannot mathematically prove that every natural-language sentence is entailed by a source. The live development suite and manual review cover this semantic risk. English is the initial supported language. There are no chat cart mutations, tools, search or external facts in Phase 5.

## Verification

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pdatabase-tests test
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,chat-smoke
```

Or choose **AI Cart - Chat smoke** in IntelliJ. It exits after running; choose **AI Cart - Backend (dev)** for the persistent web server.

The smoke suite uses `evaluation/chat-development.json`, creates its own sessions, calls Gemini, verifies routing/constraints/replay/history and unchanged carts, then ends those sessions. Its report is `target/chat-development-results.json`. Failed checks exit nonzero. This suite is separate from the untouched Phase 4 held-out retrieval set.

Database tests create randomly named isolated schemas, apply V1–V6, exercise ledger locks/retries/rollback/pagination/session lifecycle and remove only those schemas. They do not call Gemini. Normal tests cover planner/answer validation, model repair budgets, HTTP envelopes and no cart mutations.

Phase 6 extends the existing ChatRequest ledger with action results and transactional cart tool execution. Phase 7 integrates the chat UI.

Implementation verification (2026-10-23): the 149-test regression suite, including isolated database
checks, passed; the 20 affected chat tests also passed after the final adapter fixes. The resumed live
development suite passed 12/12 turns. A normal web-server run additionally verified the real chat
endpoint, global envelope, completed-request replay, history, strict input and session requirement.
Verification sessions were removed and the temporary web server was stopped.

The measured results are archived in [phase5-development-results.json](evaluation/phase5-development-results.json).
The generic vegetarian-curry portion of the mixed-scope case returned NO_EVIDENCE at the existing
retrieval threshold; the unrelated code request was correctly excluded. These development checks
do not establish recall or grounding for every possible user query, and the Phase 4 held-out set remains untouched.
