# Phase 2 implementation plan

Build the non-AI backend foundation: a sample menu, anonymous 24-hour sessions, and persistent carts. The frontend consumes the API contract in [README](../README.md#phase-2-api).

## Database and identifiers

- Keep V1-V3 unchanged. V2 creates `menu_item`, `guest_session`, `cart`, and `cart_item`; V3 inserts six fixed menu items across development and production. V4 converts menu IDs to BIGINT identity values while preserving menu data and cart references.
- Use `SESSION_<random UUID>` as the opaque session token and `CART_<random UUID>` as the persistent cart ID. A separate cart table provides exactly one cart per session. Menu IDs use `Long` with `@GeneratedValue(strategy = GenerationType.IDENTITY)`; internal cart-item row IDs remain UUIDs.
- V4 maps sample dishes to IDs 1-6 and custom dishes above 6, then advances the identity sequence. The API accepts positive numeric menu IDs and returns numeric `itemId` values. Future ingestion must preserve the assigned database IDs when updating menu items.
- Enforce unique `(cart_id, menu_item_id)`. Because each session has exactly one cart, this also guarantees one row per session/dish pair. Session deletion cascades through its cart and items.
- Store prices as `NUMERIC(10,2)`/`BigDecimal`, arrays for ingredients and dietary tags, spice levels 0–5, UTC timestamps, and JSONB context initialized to `{}`. Currency is INR.
- Flyway owns schema changes; Hibernate validates mappings. Stored quantities are 1–20; zero removes a row.

## Implementation structure

- Follow layer packages: `entity`, `repository`, `service`, `controller`, `dto`, `advice`, `exception`, `config`, and `util`.
- Define `HealthService`, `SessionService`, and `CartService` interfaces with matching `*ServiceImpl` implementations in the same service package. Use Lombok dependency constructors and accessors.
- All DTO names end in `Dto`, including `HealthStatusDto`, `SessionDto`, `CartDto`, `CartItemDto`, and `CartItemRequestDto`.
- Use Lombok classes rather than records for DTOs and API envelopes. Keep one empty line between DTO/entity fields. Every service implementation uses SLF4J logging for useful operations and failures; session tokens and credentials must not appear in logs.
- Put `ApiResponse`, `ApiError`, `GlobalResponseHandler`, and `GlobalExceptionHandler` under `advice`. Wrap JSON in `{timestamp,data,error}` once, preserve HTTP statuses, and keep 204 empty. The health status moves to `data.status`.

## Behavior

- `POST /api/v1/sessions`: atomically create a random session and empty cart; return 201 with session ID and expiry.
- Shared validation checks `X-Session-Id` and rejects invalid/unknown/deleted sessions with 401; expiry is fixed at 24 hours, not sliding.
- `GET /api/v1/cart`: return persistent cart ID, dishes, quantities, current unit prices, line totals, availability, total, and currency.
- `PUT /api/v1/cart/items/{itemId}`: set absolute quantity 0–20. Repeating the same request is safe. Reject invalid integers, unknown dishes, and positive quantities for unavailable dishes; allow removal of unavailable dishes.
- `DELETE /api/v1/sessions/current`: delete the validated session and its cart transactionally; return 204.
- Lock the session row during cart mutations and session deletion to serialize same-session writes, including the first insert of a dish. Read cart/menu data inside the transaction because Open EntityManager in View is disabled.
- Always calculate totals using database prices. Unavailable existing dishes remain visible; no silent cart mutation occurs on reads.

## Verification and completion

- Default tests require no live credentials. Check response/error contracts, strict quantity input, 0/20 boundaries, token format, and exact expiry behavior.
- Opt-in PostgreSQL checks use the same Neon database and an isolated, randomly named schema. Verify the V3-to-V4 upgrade with preexisting cart/custom-menu data, generated Long IDs, JSON/arrays, decimal totals, isolation, constraints, concurrent updates, unavailable items, cascade deletion, and persistence after restarting the application context. Drop only the test schema afterward.
- Document seed IDs and PowerShell API examples. Manually verify two separate sessions, add/update/remove/retry, totals, restart persistence, and invalidation after deletion.
- No menu-list endpoint, AI calls, ingestion, chat, or frontend changes in this phase. Periodic session cleanup remains Phase 8.
- Commit, push, and deployment are separate actions after review.

## Current phase status

- The user verified the session/cart APIs and their persisted data on 2026-10-20.
- Phase 2 feature implementation is complete. Apply V4 on the next application startup and verify a cart request using a numeric menu ID to finish checking this ID change in the application environment.
- Commit/push and deployment verification remain release steps. The complete frontend cart/session UI remains Phase 7; knowledge ingestion is Phase 3.
