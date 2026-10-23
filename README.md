# AI Cart backend

Spring Boot API for the restaurant RAG application. Phase 1 is deployed and connected. Phase 2 adds anonymous sessions, persistent carts, sample menu data, and a shared API response/error format. See [the Phase 2 implementation plan](docs/phase-2.md).

Phase 3 adds validated menu/guide sources, repeatable embedding ingestion, stable source IDs, and atomic knowledge-base updates. See [the Phase 3 ingestion guide](docs/phase-3.md) for source review, dry-run/apply commands, and tests. Normal startup does not ingest content.

Phase 4 adds exact database filters, separate menu/guide retrieval, explicit empty/partial evidence outcomes, and a development inspection API. See [the Phase 4 retrieval guide](docs/phase-4.md) for Postman examples, the IntelliJ evaluation configuration, and measured development results.

Phase 5 adds grounded English chat, automatic MENU/GUIDE/BOTH routing, session preferences, follow-up references, persisted history and reliable request retries. See [the Phase 5 chat guide](docs/phase-5.md) for the chat contract.

Phase 6 adds single-dish cart changes through native AI tools, validated references, atomic action/history persistence and duplicate-request replay. See [the Phase 6 cart tool guide](docs/phase-6.md) for Postman examples, limits and the IntelliJ cart tool smoke configuration.

## Requirements

- Java 21 for local development
- A Neon PostgreSQL database with a user permitted to create the `vector` extension
- A Gemini Developer API key
- Docker for the Render deployment path

Spring Boot 4.1.1 and Spring AI 2.0.1 are managed in `pom.xml`. The `dev` profile uses `gemini-3.5-flash-lite` for chat, while `prod` uses `gemini-3.8-flash`. Both use `gemini-embedding-001` at 768 dimensions for embeddings. Changing the embedding model or dimensions after ingestion will require rebuilding stored vectors.

## Configuration

For local development, edit the dummy values in the root `.env` file. The `dev` profile automatically imports it as Java properties. Keep values unquoted and do not use `export`. When cloning the repository, copy `.env.example` to `.env` first. System environment variables override values from this file. On Render, set the variables directly in the service settings.

| Variable | Purpose |
| --- | --- |
| `DB_JDBC_URL` | Neon JDBC URL, including `sslmode=require` |
| `DB_USERNAME` | Neon database user |
| `DB_PASSWORD` | Neon database password |
| `GOOGLE_API_KEY` | Gemini Developer API key for chat and embeddings |
| `FRONTEND_ORIGIN` | Exact React origin for CORS; `dev` allows this origin plus `http://localhost:5173`, while `prod` allows only this configured origin |
| `SPRING_PROFILES_ACTIVE` | `dev` locally, `prod` on Render |
| `PORT` | HTTP port supplied by Render; defaults to `8080` in `prod` |

The same `GOOGLE_API_KEY` is assigned explicitly to both the chat and embedding connection properties. Gemini Developer API authentication does not require a Google Cloud project ID or location. Keep `user` and `password` out of `DB_JDBC_URL`; use `DB_USERNAME` and `DB_PASSWORD` so startup logs do not include credentials in the JDBC URL.

`application.properties` contains basic shared settings and the `/api/v1` servlet context path. `application-dev.properties` imports `.env`; `application-prod.properties` uses deployment environment variables and Render's `PORT`. The production profile does not import `.env`. Hikari uses Spring Boot's default connection timeout. Flyway enables pgvector, creates the Phase 2 tables, and seeds the sample menu. Both profiles use `ddl-auto=validate`: Hibernate validates the schema and Flyway changes it.

Code follows packages by layer: `controller`, `service`, `dto`, `entity`, `repository`, `advice`, `exception`, `config`, and `util`. Services have interfaces and matching `*ServiceImpl` classes in `service`. DTO names end in `Dto`; DTOs and API envelopes use Lombok classes instead of records. Keep one blank line between DTO/entity fields. Lombok generates dependency constructors, accessors, and SLF4J loggers. Every service implementation logs useful operations and failures without logging session tokens or credentials. Normal operations use INFO, detailed validation/read activity uses DEBUG, and failed database health checks use WARN. To see service DEBUG logs locally, set `logging.level.com.rutikrokade.projects.aicartapp.service=DEBUG` in `application-dev.properties`.

## Run and verify locally

On Windows, replace the dummy values in `.env`, then run from the project root:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
Invoke-RestMethod http://localhost:8080/api/v1/health
```

For IntelliJ, run `AicartappApplication` with the working directory set to the project root. The default `dev` profile loads `.env` without an IDE environment plugin. Tests and builds need no live credentials and do not package `.env`:

For normal Run or Debug, select **AI Cart - Backend (dev)** in the IntelliJ toolbar. Shared configurations under `.run/` also provide **AI Cart - Ingest preview**, **AI Cart - Ingest apply**, and **AI Cart - Knowledge smoke**. These tasks finish and exit intentionally; use the backend configuration to keep the HTTP server running. Rerun and Stop buttons are the normal controls for a running process.

```powershell
.\mvnw.cmd clean package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar
```

The JAR also loads `.env` when started in the project root with the default `dev` profile. For production, select `prod` and provide real environment variables. On macOS or Linux, use `./mvnw` instead. `GET /api/v1/health` returns the shared envelope with `data.status = "UP"` and HTTP 200 when a database connection is valid, or `data.status = "DOWN"` and HTTP 503 when it is not. It does not call Gemini. Render checks the HTTP status; the frontend must now read `response.data.status` instead of the former top-level `response.status` (for Axios, use `response.data.data.status`).

Run the model checks explicitly, only after the database and API key are configured:

```powershell
$env:SPRING_PROFILES_ACTIVE='dev,model-smoke'
.\mvnw.cmd spring-boot:run
```

The runner makes one chat request and one embedding request, verifies a nonempty chat response and 768 embedding dimensions, and prints a success line. Stop the process after the result and reset `SPRING_PROFILES_ACTIVE=dev`. Do not enable `model-smoke` on Render.

## Docker and Render

The multistage Dockerfile builds the JAR with Maven and runs it on Java 21. To exercise the deployment path locally, build the image and supply the same environment variables to `docker run`:

```powershell
docker build -t ai-cart-backend .
docker run --rm -p 8080:8080 --env-file .env -e SPRING_PROFILES_ACTIVE=prod ai-cart-backend
```

For this Docker example, fill in the existing `.env` and set `FRONTEND_ORIGIN` to the origin used for the test. Docker's `--env-file` passes those values as environment variables to the production profile. `.env` is ignored by Git and excluded from the image build context.

Create a Render **Web Service** from this repository using the Docker runtime and the main branch. Set `SPRING_PROFILES_ACTIVE=prod`, the five required connection/origin variables, and HTTP health check path `/api/v1/health`. Render supplies `PORT`. The backend API base URL is `https://<service-name>.onrender.com/api/v1`; provide that URL to the frontend. Set `FRONTEND_ORIGIN` to the exact deployed frontend origin without a trailing slash. Deploy the frontend independently and verify a browser request to `/api/v1/health` from that origin.

The standard Maven test command does not require live credentials. Docker packages with tests skipped; run `.\mvnw.cmd test` before deploying. The live database and Gemini checks are explicit verification steps, not build steps.

## Phase 2 API

Successful JSON responses use `{"timestamp":"<UTC instant>","data":{...},"error":null}`. Errors use `{"timestamp":"<UTC instant>","data":null,"error":{"status":400,"code":"VALIDATION_ERROR","message":"Request validation failed","errors":[{"field":"quantity","message":"Quantity must be between 0 and 20"}]}}`. HTTP status codes are preserved. HTTP 204 has an empty body.

| Method and path | Input | Result |
| --- | --- | --- |
| `POST /api/v1/sessions` | No body or session header | 201; `data.sessionId` and `data.expiresAt` |
| `GET /api/v1/cart` | `X-Session-Id` | 200; current cart |
| `PUT /api/v1/cart/items/{itemId}` | `X-Session-Id`, JSON `{"quantity":2}` | 200; updated cart |
| `DELETE /api/v1/sessions/current` | `X-Session-Id` | 204; session and its cart removed |

Session IDs look like `SESSION_<random UUID>`, and cart IDs like `CART_<random UUID>`. A session owns exactly one cart, created in the same transaction, and expires 24 hours after creation without extending on requests. Send the session ID only in `X-Session-Id`, not in URLs or logs. Store it in the frontend's `sessionStorage` when integrating session support.

`PUT` sets an absolute quantity from 0 through 20; zero removes the item. Sending the same quantity again does not increment it. Missing/null, fractional, string, negative, and excessive quantities are rejected. Only the database supplies prices and totals. An empty cart has `items: []`, `total: 0.00`, and `currency: "INR"`.

Example cart data inside the response envelope:

```json
{
  "cartId": "CART_01234567-89ab-4cde-8f01-23456789abcd",
  "items": [{
    "itemId": 1,
    "name": "Paneer Butter Masala",
    "quantity": 2,
    "unitPrice": 250.00,
    "lineTotal": 500.00,
    "available": true
  }],
  "total": 500.00,
  "currency": "INR"
}
```

Missing, malformed, unknown, and deleted session tokens return 401 with `INVALID_SESSION`; expired tokens return 401 with `SESSION_EXPIRED`. Unknown dishes return 404 `ITEM_NOT_FOUND`. Positive quantities for unavailable dishes return 409 `ITEM_UNAVAILABLE`. Existing unavailable dishes remain visible, use current database prices in totals, and can be removed. Removing a known dish already absent from the cart succeeds. Periodic expiry cleanup is scheduled for Phase 8.

Sample dishes are seeded by Flyway in both development and production. Menu IDs use Java `Long` and PostgreSQL `BIGINT GENERATED BY DEFAULT AS IDENTITY`, mapped with `@GeneratedValue(strategy = GenerationType.IDENTITY)`. New menu items receive IDs from the database; callers must treat IDs as opaque values rather than assuming they are contiguous. There is no menu-list endpoint in Phase 2; use these sample IDs for manual checks:

| Item ID | Dish | INR | Available |
| --- | --- | ---: | --- |
| `1` | Paneer Butter Masala | 250.00 | Yes |
| `2` | Dal Tadka | 180.50 | Yes |
| `3` | Chicken Biryani | 320.00 | Yes |
| `4` | Vegetable Pulao | 160.00 | Yes |
| `5` | Butter Naan | 45.00 | Yes |
| `6` | Gulab Jamun | 90.00 | No |

Run the backend normally to apply pending migrations to the application schema. V2 and V3 remain unchanged for databases that have already applied them. V4 converts menu UUIDs to numeric IDs, updates existing cart references, preserves custom menu items, and advances the identity sequence past all migrated IDs. On a fresh database, Flyway applies V1 through V4 in order. Do not edit migrations after they have been applied; use a new migration for subsequent changes.

After V4, clients must send positive numeric menu IDs (for example, `PUT /api/v1/cart/items/1`). Cart response `itemId` values are JSON numbers; former menu UUID paths now return 400. Session and cart IDs retain their `SESSION_`/`CART_` format. Numeric IDs are identifiers, not authorization credentials; session validation still determines which cart can be changed.

### Manual checks in PowerShell

```powershell
$api = 'http://localhost:8080/api/v1'
$sessionA = (Invoke-RestMethod -Method Post "$api/sessions").data
$sessionB = (Invoke-RestMethod -Method Post "$api/sessions").data
$headersA = @{ 'X-Session-Id' = $sessionA.sessionId }
$headersB = @{ 'X-Session-Id' = $sessionB.sessionId }
$dishId = 1

# A: two paneer dishes, total INR 500.00; repeat to confirm quantity stays two.
Invoke-RestMethod -Method Put "$api/cart/items/$dishId" -Headers $headersA -ContentType 'application/json' -Body '{"quantity":2}'
Invoke-RestMethod -Method Put "$api/cart/items/$dishId" -Headers $headersA -ContentType 'application/json' -Body '{"quantity":2}'

# B must remain empty.
Invoke-RestMethod "$api/cart" -Headers $headersB

# Remove from A; then end A. Further requests with A's token must return 401.
Invoke-RestMethod -Method Put "$api/cart/items/$dishId" -Headers $headersA -ContentType 'application/json' -Body '{"quantity":0}'
Invoke-RestMethod -Method Delete "$api/sessions/current" -Headers $headersA
```

### Automated database checks

Normal tests (`.\mvnw.cmd test`) exercise HTTP contracts, input validation, session logic, and existing Phase 1 behavior without calling Neon or Gemini. To include PostgreSQL integration checks explicitly:

```powershell
.\mvnw.cmd -Pdatabase-tests test
```

This uses the same `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` from environment variables or `.env`. The database user must be able to create a schema. Each run creates a random `phase2_test_<UUID>` schema, applies V1-V3 and inserts a legacy cart/custom dish, then starts the application to apply V4. It checks preservation of existing data, generated menu IDs, mapping/constraints/cart isolation/concurrency/expiry/deletion/restart persistence, then drops only that test schema. Application tables are not reset or modified, and no Gemini requests are made. If the test process is forcibly terminated, its temporary schema may remain; remove only the corresponding `phase2_test_...` schema after confirming no test is running. Final application-data cleanup is a separate operation.
