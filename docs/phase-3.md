# Phase 3: knowledge ingestion

## Status and ownership

The implementation ships a **fictional demo restaurant**: 40 dishes and 10 guide topics.
Review `src/main/resources/knowledge/menu.json` and `restaurant-guide.md` before the first live apply.
The original six dishes retain their numeric IDs and original field values.

Source files own imported menu fields, including price and availability. Later imports overwrite manual
DB edits to those fields. Unmanaged rows (`source_key IS NULL`) are left alone; conflicting names fail validation.
Development and production currently share a database, so applying from either environment updates the same knowledge base.

Normal backend startup applies Flyway migrations but **does not run ingestion or request embeddings**.
The new V5 migration adopts the six menu source keys and creates the vector/state tables.
It assumes the existing Phase 1 pgvector extension is installed in `public`.

## Local commands

In IntelliJ, the shared **AI Cart - Ingest preview**, **AI Cart - Ingest apply**, and **AI Cart - Knowledge smoke** configurations run these tasks using the Run or Debug button. They exit on completion. Select **AI Cart - Backend (dev)** for the persistent API server, rather than changing its arguments for ingestion.

Run from the project root so the dev profile can load `.env`. No additional credentials are needed.
Use the existing `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `GOOGLE_API_KEY` values.
Builds and ordinary tests do not need live credentials.

```powershell
.\mvnw.cmd clean package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,ingest --app.ingestion.mode=dry-run
```

The command validates both sources, compares the published index, writes `target/ingestion-preview.json`,
and exits without starting Tomcat. The preview contains the full searchable chunks and planned operations.
No embedding requests or menu/vector writes occur in dry-run. Pending Flyway schema migrations can still run.

After reviewing the source files and preview:

```powershell
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,ingest --app.ingestion.mode=apply
```

Run the same command again: expect **0 embedded documents**, 50 unchanged documents, and the same published revision.

For a deployment environment supplying environment variables, use `prod,ingest` instead.
A Render web service must continue using its normal `prod` profile; ingestion is a separate one-off command.
Do not add `ingest` to its normal startup profiles.

To read edited local files without rebuilding the JAR:

```powershell
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,ingest --app.ingestion.mode=dry-run --app.ingestion.menu=file:./src/main/resources/knowledge/menu.json --app.ingestion.guide=file:./src/main/resources/knowledge/restaurant-guide.md
```

Use the same source overrides for the following apply. Without overrides, the packaged classpath files are used.
The metadata uses logical source paths, so moving a checkout does not change document identity.

## Content and chunking

- Menu is a complete JSON array, not a partial patch.
- Each dish has an immutable lower-case kebab-case `sourceKey`; do not rename it when editing the dish name.
- Keep source keys unique. Dish names must also be distinct after trimming/collapsing whitespace and ignoring case.
- Prices are nonnegative INR amounts with at most two decimal places; spice is an integer from 0 through 5.
- Use `VEGETARIAN`, `VEGAN`, or `NON_VEGETARIAN` tags. Non-vegetarian tags cannot coexist with the first two.
- One complete dish is one document. Text contains name, category, description, sorted ingredients/tags and spice label.
- Price, availability, IDs, timestamps, sessions, carts and chat history are excluded from embedded text.
- Each guide `## Heading {#stable-key}` is one document, including its title and all paragraphs/lists/caveats.
- Subheadings inside a topic stay in that topic. Oversized topics must be split into separately identified `##` sections.
- No overlapping windows. A 600-token estimate using CL100K_BASE rejects oversized chunks locally; this is not Google's tokenizer.
- Missing/empty source files, duplicate JSON fields, unknown menu properties, coerced boolean/integer strings,
  invalid values, duplicate identifiers and oversized topics fail before application-data writes or provider calls.
- Human review remains necessary for recipe accuracy, dietary suitability and restaurant policies.

## Identity and incremental updates

Menu IDs remain generated `Long` values. `source_key` maps source entries to those IDs.
Vector IDs are deterministic Java name-based UUIDs from `aicart:menu:<key>` or `aicart:guide:<key>`.
SHA-256 is calculated over the canonical embedded text. Reordering JSON or ingredient lists does not change the hash.

| Change | Result |
|---|---|
| Add one dish | One generated menu ID and one new embedding |
| Rename/describe/change ingredients, tags, category or spice | Same menu/vector IDs; one replacement embedding |
| Price/availability only | Menu update; zero embeddings |
| Guide topic edit | Only that topic is re-embedded |
| Metadata-only change | Metadata update; zero embeddings |
| Missing vector | Recreated even if the source hash is unchanged |
| Remove dish from complete source | Retain menu row, set source_active/available false, delete its vector |
| Reintroduce its original source key | Restore same menu ID and recreate vector |
| Remove guide topic | Delete that owned vector |
| Unchanged run | No vector/menu writes or new revision |

Temporary unavailability keeps the vector. Retrieval in Phase 4 must consult the live menu DB and exclude unavailable
items when appropriate. Retired rows remain for cart foreign keys. Existing cart APIs still read current price/availability.

## Embeddings and publication

The primary Spring AI `EmbeddingModel` adapter uses the installed Google SDK directly because the bundled Spring AI
Google text adapter does not forward its task-type option. Chat continues using its existing integration.

The embedding adapter owns and closes its SDK client. It is not exposed as a shared Spring Client bean:
Spring AI's cache condition can eagerly create shared clients before @Value placeholder resolution.
Embedding settings are resolved explicitly through Spring's Environment, including nested .env references.

- `gemini-embedding-001`, 768 dimensions.
- Document and document-batch methods send `RETRIEVAL_DOCUMENT`; string/list query methods send `RETRIEVAL_QUERY`.
- Metadata is not appended to provider input.
- Both paths validate finite, nonzero vectors and normalize them to unit length.
- Requests are sequential, with a 30-second HTTP call timeout and at most three attempts for network errors, 429 and 5xx.
- Backoff starts at one second; Retry-After is honored up to 30 seconds, within the call timeout.
- SDK retries are disabled so retry layers do not multiply calls. Other HTTP client errors are not retried.
- HTTP failures include the status code and a safe error category (authentication, permissions, quota or model/request
  configuration). Raw provider response bodies, credentials and request text are never logged.

The service validates the whole snapshot, reads a consistent DB snapshot and creates the change plan.
It prepares required embeddings in memory **outside the publication transaction**.
Publication locks the corpus state, checks the revision, writes changed menu rows, writes prepared vectors,
retires removed dishes, prunes owned vectors and increments the revision in one transaction.

The JDBC vector writer uses the Spring AI-compatible table. Calling `PgVectorStore.add()` here would generate
embeddings again, so it is deliberately not used for publication. Spring AI PGvector remains available for retrieval.
Cosine distance with index type NONE is appropriate for this initial small corpus.

An embedding failure leaves the previous publication unchanged. A SQL failure rolls the transaction back.
Concurrent publication produces a revision-conflict error; rerun against the new state.
Retries after an aborted run can repeat provider calls because there is no persistent staging cache.
Only vectors with `corpusId=restaurant-v1` are pruned. A deterministic ID collision with another corpus fails publication.

The fingerprint includes model, dimensions, task pairing, normalization and renderer/chunker versions.
A mismatch blocks ordinary apply. Dry-run previews the required full rebuild without making provider calls.
After reviewing a same-dimension configuration change, use:

```powershell
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,ingest --app.ingestion.mode=rebuild
```

Rebuild replaces all owned vectors atomically after preparing them.
Changing dimensions also requires a deliberate Flyway schema migration and matching adapter update; configuration alone is rejected.
Coordinate model changes with the serving application so queries and the published index use the same model.

## Verification

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pdatabase-tests test
```

Ordinary tests use fake embeddings and a local HTTP server to inspect real SDK request JSON.
Opt-in DB tests use credentials from the environment or `.env`, create generated `phase2_test_*` / `phase3_test_*` schemas,
and remove only those schemas afterward. They never ingest into the application schema.

Coverage includes source validation, stable hashes/IDs, zero-call repeat runs, selective updates, removals/restoration,
existing cart references, provider failure, SQL rollback, revision conflicts, metadata repair, and SDK task types/normalization.

Before marking the live knowledge base ready:
1. Review the demo sources and run dry-run.
2. Apply and confirm 40 menu documents plus 10 guide documents.
3. Apply again and confirm zero embedding calls.
4. Run the opt-in retrieval smoke command below. It sends two real query-embedding requests and logs the top five
   menu and guide source IDs, numeric menu links, and scores. Inspect relevance; the command checks retrieval wiring
   and valid links, while comprehensive filtering and evaluation remain in Phase 4.

```powershell
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,knowledge-smoke
```

The smoke command refuses an unpublished index or a mismatched embedding fingerprint, starts no web server, and exits.
Use it after apply, never in a Render web service's ordinary startup profiles.
Unit/database tests use deterministic fake vectors and do not claim semantic-search quality.

Logs show source identifiers, preview counts, publication revision, embedded/skipped/deleted counts and duration.
Commands fail with a nonzero exit code when validation, embedding or publication fails.
No public ingestion API has been added.
