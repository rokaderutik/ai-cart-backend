# AI Cart backend

Spring Boot API for the restaurant RAG application. Phase 1 is complete: the backend and separate React frontend are deployed and connected, and Neon, Gemini chat, and embedding connectivity have been verified.

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

`application.properties` contains the application name, the shared `/api/v1` servlet context path, and the default `dev` profile. `application-dev.properties` imports `.env`; `application-prod.properties` uses deployment environment variables and Render's `PORT`. The production profile does not import `.env`. Hikari uses Spring Boot's default connection timeout. Flyway's first migration enables pgvector; future table migrations belong to later phases.

Code follows packages by layer: `controller` handles HTTP requests, `service` performs database checks, `dto` defines response data, `config` configures CORS, and `util` contains the explicit model smoke runner. Lombok generates dependency constructors, configuration accessors, and the smoke runner's logger.

## Run and verify locally

On Windows, replace the dummy values in `.env`, then run from the project root:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
Invoke-RestMethod http://localhost:8080/api/v1/health
```

For IntelliJ, run `AicartappApplication` with the working directory set to the project root. The default `dev` profile loads `.env` without an IDE environment plugin. Tests and builds need no live credentials and do not package `.env`:

```powershell
.\mvnw.cmd clean package
java -jar target/aicartapp-0.0.1-SNAPSHOT.jar
```

The JAR also loads `.env` when started in the project root with the default `dev` profile. For production, select `prod` and provide real environment variables. On macOS or Linux, use `./mvnw` instead. `GET /api/v1/health` returns `{"status":"UP"}` with HTTP 200 when a database connection is valid, or `{"status":"DOWN"}` with HTTP 503 when it is not. It does not call Gemini. The endpoint is intended for the frontend's backend status display and Render's HTTP health check.

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
