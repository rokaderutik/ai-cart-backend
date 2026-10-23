# Phase 6 — Cart operations through chat

The existing `POST /api/v1/chat` now supports one explicit dish change per message.
The backend applies an unambiguous instruction directly. Several dishes, unknown
names, missing references, unclear quantities, and conditional or combined
recommendation/action instructions require clarification without a partial write.
Checkout and order placement remain unsupported.

## Try it

Run **AI Cart - Backend (dev)** in IntelliJ. Normal `dev` and `prod` startup applies
additive migration V7; it adds `action_result_json` to the existing request ledger.
Existing menu, vectors, carts and sessions are preserved. No re-ingestion or new
credentials are needed. The configured profile's chat model must support native
function calling.

Create a session with `POST /api/v1/sessions`, then send the following messages to
`POST /api/v1/chat`, using the same `X-Session-Id` header and a new request ID for
each message:

```json
{
  "requestId": "{{$guid}}",
  "message": "Suggest at least two mild vegetarian main dishes under 300 rupees."
}
```

Subsequent message bodies use the same shape:

| Message | Expected change |
|---|---|
| Add the first dish. | Add one unit of the first returned recommendation |
| Make that two. | Set that dish's total quantity to two |
| Add one more. | Increase its total to three |
| Remove one. | Decrease its total to two |
| Make that two. | Successful no-change result |
| Remove it. | Remove that line |

Compare `data.cart` with `GET /api/v1/cart` after each change. Use
`GET /api/v1/chat/messages` to inspect persisted replies and action metadata.
For a retry test, replace `{{$guid}}` with a fixed UUID and send the **identical**
body twice. Only the first request may change the cart. A fresh ID means a new
instruction, so repeating an ADD message with a new ID intentionally adds again.

Try `Add the first and second dishes`, `Add Moon Rock Soup`, `Make that 21`, and
`Checkout` to verify split-request, unknown-item, quantity-limit and unsupported
operation responses. Removal of an unavailable cart item is supported.

## Response contract

The request shape, header, endpoints and global `{timestamp,data,error}` envelope
are unchanged. `data.actionResult` is an additive nullable field:

```json
{
  "operation": "SET",
  "itemId": 12,
  "previousQuantity": 1,
  "quantity": 2,
  "outcome": "CART_UPDATED",
  "reason": null
}
```

The ID above is illustrative; use actual returned menu IDs. `quantity` is the
resulting quantity, including the unchanged quantity on rejection.

- `CART_UPDATED`: one line changed.
- `CART_UNCHANGED`: the requested absolute quantity already matched.
- `CART_ACTION_REJECTED`: business rejection, with `INVALID_QUANTITY`,
  `CART_CHANGED`, `ITEM_NOT_FOUND` or `ITEM_UNAVAILABLE` in `reason`.
- `CLARIFICATION`: no tool ran; the response asks for missing information.
- `CART_ACTION_UNAVAILABLE`: checkout/order placement is unsupported.

These conversational outcomes return HTTP 200 with a saved assistant message.
Existing session errors, request-ID conflicts and processing conflicts retain
their HTTP error status. Provider/invalid model output failures return a safe
503 before any cart mutation. A failed request may be retried under the existing
Phase 5 rules.

Replayed responses contain the original cart snapshot and action result. A later
manual edit is not overwritten by replay; fetch `/cart` for its current state.

## Execution and safety

1. Claim the existing request ledger entry and load current cart/context.
2. The v2 schema planner proposes an operation and reference. Java validates
   literal named items, saved recommendation positions, last cart action and
   unambiguous single-line cart references. Unknown names are never substituted
   with vector matches. Pending cart clarification retains the operation/amount.
3. Java computes the absolute target quantity from the observed cart. ADD and
   DECREASE use a positive amount; SET accepts 0–20 and REMOVE targets zero.
   Out-of-range requests are rejected, not clamped. A menu knowledge turn or
   general clarification clears stale cart focus; guide turns preserve it.
   A rejected change also clears the prior successful cart focus and retains
   only the rejected item's reference, so a correction cannot select an older dish.
4. A separate native function-calling request receives only the prepared item ID
   and absolute quantity. The explicit Spring AI `ToolCallback` exposes
   `setCartItemQuantity`. Exactly one call must match the prepared values; strict
   parsing rejects unknown fields, duplicate keys, scalar coercions and fractions.
5. The application invokes the callback with backend-owned `ToolContext` containing
   session, request attempt and prepared state. These are not model arguments.
   No automatic advisor loop or global tool exposure is enabled.
6. A short transaction locks the active session, validates its request attempt
   and lease, checks the item's observed quantity and locks its menu row. A manual
   quantity change during model processing gives `CART_CHANGED`. CartService
   performs the same mutation used by the normal REST API.
7. That transaction saves the cart, action outcome, assistant reply, context and
   completed request together. Database prices determine totals. If completion
   fails, all those writes roll back. Lost HTTP responses replay the saved result.

Java builds the final action confirmation directly from the transaction result.
No model call happens after the write, and no database transaction stays open
while waiting for a model. Normal cart actions use two chat calls (planner plus
native call generation), with one shared repair allowing at most three. Cart
actions do not use embeddings. SDK and Spring transport retries remain disabled.

Logs include request ID, provider stage, sanitized provider HTTP status, validation
category, validated item/quantity and outcome. A provider 429 logs `RATE_LIMIT`;
invalid structured output remains a separate validation path. Prompts, credentials
and session tokens are not logged by the new action path.

## Verification

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pdatabase-tests test
.\mvnw.cmd package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,cart-tool-smoke
```

Or select **AI Cart - Cart tool smoke** in IntelliJ. This runner exits after the
check; use **AI Cart - Backend (dev)** for the persistent server. The runner calls
the real configured model and database, creates only its own session/cart, checks
recommendations → add → set → increment → decrease → no-change → remove, verifies
replay/history, then deletes its session. It also checks unknown/multiple dishes
and checkout. Its report is `target/cart-tool-development-results.json`.
It pauses ten seconds between turns to reduce quota bursts; override
`app.cart-tool-smoke.pause-millis` when necessary. It stops on provider failures
and cleans up its session; it does not repeatedly retry a quota error.

Automated tests exercise strict native-call arguments, shared repair limits,
real SDK HTTP serialization, provider 429 handling, controller envelopes, references,
context transitions and existing chat behavior. Isolated database tests cover
atomic rollback, unavailable items, manual edits, two sessions, lease fencing and
concurrent duplicate execution. They create and remove randomly named test schemas.
The previous chat smoke suite now expects real cart behavior for its cart scenario.
The Phase 4 held-out evaluation set remains reserved for Phase 8.

Verification on 2026-10-23: the full 167-test regression suite passed, followed by
40 affected tests and a final 31-test run covering the rejection-reference fix.
All test runs had zero failures or errors. The final application package also
built successfully. The HTTP fixture exercises the real Google SDK with simulated
native responses and 429 errors; database tests use isolated schemas and no model.

The final paced live smoke passed **10/10 turns**, including each request's replay
and history checks. It deleted its temporary session and stopped successfully.
Measured results are archived in
[phase6-development-results.json](evaluation/phase6-development-results.json).
An earlier unpaced run hit a genuine provider 429 after nine completed turns;
it stopped safely. The final run passed with the documented smoke pacing.
Deployment verification is a separate release check.
