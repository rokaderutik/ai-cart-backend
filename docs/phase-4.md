# Phase 4: retrieval before generation

## What is implemented

`RetrievalService` accepts a standalone query, an explicit MENU/GUIDE/BOTH target, and validated filters.
It finds evidence and current menu fields; it does not generate an answer or classify user intent.
The current corpus contains 40 menu documents and 10 guide sections. No migration or re-ingestion is required.

1. Copy, trim and validate the request, including service callers that bypass HTTP.
2. Read the published revision/fingerprint and reject an unpublished or incompatible index.
3. Embed each distinct branch query with the existing RETRIEVAL_QUERY adapter. Identical branch queries share one embedding.
4. Start a short read-only REPEATABLE_READ transaction and check the fingerprint again.
5. Join menu vectors to active source-owned menu rows by item ID and source key. Apply all exact filters before ranking.
6. Search guide vectors independently. Both queries restrict corpusId and sourceType.
7. Rank using cosine similarity, apply each branch's threshold, and return at most five documents per branch.
8. Return current menu data, full chunks, display snippets, approved metadata, outcomes, revision and timings.

The JDBC repository uses Spring AI-compatible vector_store data directly so exact SQL filters, live menu data,
and both branches share one database snapshot. No query vectors are persisted. No menu, cart, session or vector rows
are changed. Network embedding requests happen outside the transaction. Ties are ordered by source ID.

Availability and prices describe that snapshot; the cart service still checks them again when a user changes the cart.
An index mismatch, missing eligible menu vector, duplicate linked vector, incompatible vector, or absent guide corpus
returns 503. This is not evidence that a user's question has no answer.
Partial deletion of guide sections cannot be inferred from the remaining index alone; ingestion restores missing source documents.

## Try it in Postman or IntelliJ

Select **AI Cart - Backend (dev)** and click Run. It stays running. No new secrets or environment values are required.
Send JSON with Content-Type application/json to:

```http
POST http://localhost:8080/api/v1/dev/retrieval
```

The endpoint requires no session and is only registered when dev is active and prod is absent.
Production has no inspection endpoint, even if both profiles are accidentally enabled.
The service is available for the later production chat pipeline. This phase does not add a public chat API.

Menu example:

```json
{
  "target": "MENU",
  "query": "A creamy vegetarian main with mild spice",
  "filters": {
    "maxPrice": 300,
    "dietaryTags": ["VEGETARIAN"],
    "categories": ["MAIN"],
    "minSpiceLevel": 0,
    "maxSpiceLevel": 1,
    "availableOnly": true
  }
}
```

Guide example:

```json
{"target":"GUIDE","query":"What are your takeaway packaging arrangements?"}
```

Mixed example:

```json
{
  "target": "BOTH",
  "query": "Suggest a mild vegetarian main under 300 and explain cream removal",
  "menuQuery": "Creamy mild vegetarian main",
  "guideQuery": "Can cream be removed from a dish as a customization?",
  "filters": {
    "maxPrice": 300,
    "dietaryTags": ["VEGETARIAN"],
    "categories": ["MAIN"],
    "maxSpiceLevel": 1
  }
}
```

Useful empty-result checks:

```json
{"target":"MENU","query":"Vegetarian main","filters":{"categories":["MAIN"],"maxPrice":20}}
```

```json
{"target":"BOTH","query":"Explain the formation of black holes"}
```

### Request rules

| Field | Meaning |
| --- | --- |
| target | Required MENU, GUIDE or BOTH |
| query | Required standalone text, 1-1000 characters after trimming |
| menuQuery / guideQuery | Optional overrides, only valid for BOTH; absent values use query |
| filters.maxPrice | Inclusive maximum per item in INR, 0-99999999.99, at most two decimals |
| filters.dietaryTags | All requested tags must be present; VEGETARIAN, VEGAN, NON_VEGETARIAN |
| filters.categories | Any requested category: MAIN, RICE, BREAD, DESSERT, STARTER, SIDE, DRINK |
| filters.minSpiceLevel / maxSpiceLevel | Inclusive integer bounds within 0-5 |
| filters.availableOnly | Defaults to true; false includes temporarily unavailable dishes |

Empty tag/category lists impose no restriction. Retired and unmanaged menu rows are always excluded.
GUIDE requests reject filters. Dietary contradictions and invalid bounds return 400.
HTTP input rejects unknown/duplicate properties, numeric enums, fractional spice values and scalar strings pretending to be numbers/booleans.
An absent tag is not inferred from ingredients. The original Chicken Biryani has no NON_VEGETARIAN tag and cannot satisfy that explicit filter.

Writing "under 300" only in query does not enforce a price bound. You must supply maxPrice in this phase.
No constraints are relaxed automatically. A semantic query is not an exact dish-identity lookup; a nearby result
must not later be presented as the exact named dish if its identity differs.

### Response contract

The existing envelope is `{timestamp, data, error}`. `data` contains:

- requestId, status, appliedFilters (null for GUIDE).
- menu and guide branches (null when not requested).
- Each branch: outcome, eligibleCount, threshold, documents.
- Each document: documentId, sourceId, sourceType, title, score, content, snippet, metadata, menuItem.
- menuItem is null for guides; otherwise it includes current Long id, name, description, category, ingredients,
  dietaryTags, spiceLevel, price, currency and available.
- metadata contains corpusId, sourcePath and contentHash. Internal metadata is not copied blindly.
- corpusRevision, embeddingFingerprint, embeddingCalls, embeddingMillis, databaseMillis and totalMillis.

Snippets show at most 240 characters plus an ellipsis. Full content retains the original guide caveats for Phase 5.
The database timing covers readiness and snapshot retrieval; total time additionally includes validation/assembly.
Eligible count is the number of filter-matching indexed menu rows, or the guide corpus count, before semantic thresholding.

| Branch outcome | Meaning |
| --- | --- |
| EVIDENCE_FOUND | One or more sources passed the threshold; answerability is not guaranteed |
| NO_ELIGIBLE_ITEMS | No menu rows satisfy exact filters |
| NO_RELEVANT_EVIDENCE | Eligible sources exist, but none pass the branch threshold |

Overall status is EVIDENCE_FOUND when all requested branches have evidence, PARTIAL_EVIDENCE when only one of two
branches has evidence, and NO_EVIDENCE when neither does. Successful empty/partial searches return HTTP 200.
Operational failures fail the whole request, rather than returning a misleading partial result.

| Error code | HTTP status |
| --- | --- |
| INVALID_REQUEST / INVALID_RETRIEVAL_REQUEST | 400 |
| KNOWLEDGE_NOT_PUBLISHED | 503 |
| KNOWLEDGE_MODEL_MISMATCH | 503 |
| KNOWLEDGE_INDEX_INCOMPLETE | 503 |
| EMBEDDING_UNAVAILABLE | 503 |
| RETRIEVAL_DATABASE_UNAVAILABLE | 503 |

## Missing information and the Phase 5 handoff

For BOTH, menu filters never affect guide retrieval. Independent result limits prevent one corpus from crowding out the other.
The caller supplies focused branch queries now; Phase 5 will derive them from the user's message and session context.

No eligible dish: explain the filters and ask whether the user wants to change them. Never silently raise the budget.
No relevant evidence: do not invent a menu match or restaurant policy.
Only one branch succeeds: answer the supported part and explain the missing part.

Opening hours illustrate a different case: the overview explicitly states that hours are not established.
That section is useful evidence for saying the information is unavailable. Similarly, retrieving a dish's ingredients
does not establish its calorie count. EVIDENCE_FOUND is deliberately not named ANSWER_FOUND.

Similarity is not an answer-confidence percentage or a reliable topic classifier. The development negative queries are
rejected by the calibrated thresholds, but food-related programming questions can still resemble restaurant text.
Phase 5 must classify MENU/GUIDE/BOTH/unrelated/ambiguous intent, validate model-produced filters, check factual support,
and validate citations before answering. Unrelated questions should receive a brief scope response; ambiguous ones need clarification.
Allergen/customization limitations must remain in context. A vegan tag does not establish allergy safety.

## Evaluation and configuration

Select **AI Cart - Retrieval evaluation** in IntelliJ and click Run. It performs real query embeddings, writes
`target/retrieval-evaluation.json`, and exits. It is separate from the persistent Backend configuration.

Equivalent terminal commands from the project root:

```powershell
.\mvnw.cmd package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,retrieval-eval
```

Flyway normally runs at application startup. To explicitly prevent migration execution during an evaluation of an
already migrated database, append `--spring.flyway.enabled=false` (as used for the recorded Phase 4 evaluation).
Do not add retrieval-eval to Render's normal prod profile. Never combine it with ingest or knowledge-smoke.
The runner changes thresholds temporarily to collect candidates; it must run as its own non-web process.

The fixed 30-case development resource is `src/main/resources/retrieval-evaluation/development.json`.
The 20 reserved cases are in `docs/evaluation/held-out.json`, outside application resources. The runner has no option
to select that file. They are authored now but must not be executed or used for tuning before Phase 8.

| Group | Development | Held out |
| --- | ---: | ---: |
| Semantic descriptions | 8 | 5 |
| Exact constraints | 8 | 5 |
| Guide | 5 | 3 |
| Mixed | 4 | 3 |
| Unsupported / unrelated | 5 | 4 |

Labels include stable relevant source IDs, branch outcomes, unrelated status, and whether the requested fact is supported.
Recall@5 is micro-averaged over the annotated relevant source IDs, separately per corpus. These small labels are not
an exhaustive catalog of every possibly relevant source. factSupported is an annotation for future answer evaluation;
it does not force an empty result when a related source explicitly documents a limitation.

The runner captures top-five candidates at threshold zero, once per case, then sweeps 0.40-0.90 in 0.01 increments
offline for each branch. It maximizes recall while requiring zero unrelated-case false positives, breaking ties toward
the higher threshold. If no feasible threshold exists, the candidate 0.90 is reported and the quality gate fails.
It records both calibrated and currently configured results; exit success requires both to pass.
Thresholds are never automatically written back to configuration.
During candidate capture, service logs describe threshold-zero results. Use the report's projected outcomes and final
metrics to assess relevance; an unrelated query may have raw candidates that are subsequently rejected.

Defaults, calibrated on the development set: menu **0.69**, guide **0.61**.
Optional `RETRIEVAL_MENU_THRESHOLD` and `RETRIEVAL_GUIDE_THRESHOLD` env values override them in either profile.
No additional .env values are necessary. Thresholds must be finite within 0-1.

The runner checks a stable publication across all cases, verifies referenced sources exist, and records dataset/source
hashes, revision, fingerprint, timings, embedding-call counts, raw candidates and projected branch outcomes.
A gate requires at least 80% recall per corpus, zero constraint violations, zero false positives on the three unrelated
development cases, correct labeled branch outcomes, and coverage of all mixed cases (including the intentional partial case).
Changing sources or model configuration requires re-evaluation; these measurements do not guarantee performance on arbitrary questions.

First live development capture (2026-10-22 IST, revision 1):

- Menu relevant sources: **22/22**; guide relevant sources: **11/11**.
- Mixed cases: **4/4**; unrelated questions rejected: **3/3** across both branches.
- Accepted menu results violating constraints: **0/27**.
- Query embedding calls: **35** for 30 cases.
- Retrieval latency: p50 **1140 ms**, p95 **1888 ms**, maximum **2285 ms** (excluding application startup).

See [the recorded development results](evaluation/phase4-development-results.json) for candidates and source scores.
The capture's initial thresholds were 0.65/0.65; its calibrated 0.69/0.61 settings are now the application defaults.
The held-out set was not executed.

## Tests and observability

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pdatabase-tests test
```

Ordinary tests use fake embeddings and test validation, provider failures, query reuse, API envelopes, production
endpoint exclusion and calibration. Database tests use generated phase4_test_* schemas, apply migrations, publish
fake vectors and verify exact constraints, cosine scoring, ties, corpus isolation, missing vectors, and repeatable
reads across a concurrent publication. They drop only their generated schema and make no Gemini requests.

INFO logs contain request IDs, target, structured filters, branch outcomes/counts, revision, safe errors and timings.
DEBUG logs add source IDs and scores. Raw queries, full chunks, credentials and embeddings are not logged.
The explicit evaluation report contains only the curated evaluation questions and retrieved restaurant evidence.
There is no automatic live-model evaluation in the normal build or CI.

Implementation verification (2026-10-22): 120 automated tests passed across the regression run and the targeted
rerun after aligning retrieval-service profiles with the embedding adapter. The packaged application also passed
four live HTTP checks: mixed evidence, partial evidence, unrelated-query rejection, and opening-hours limitation evidence.
The temporary HTTP server was stopped after verification.
