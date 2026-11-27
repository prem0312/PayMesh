# PayMesh — Offline Payment Mesh Demo

PayMesh is a lightweight portfolio application that demonstrates a simulated, mesh-routed deferred payment flow. A dashboard creates an encrypted packet, simulated phones gossip it to one another, and an internet-connected bridge submits it to a Spring Boot backend. The backend claims the packet idempotently and settles a demo ledger in PostgreSQL.

**This is not UPI, a bank integration, or a real payment system.** It moves only balances in a local demo database. The PIN is hashed into the encrypted demo instruction but is not verified and does not authenticate a user.

## Requirements

- JDK 21 or newer (the Maven build targets Java 21).
- PostgreSQL 16 recommended, running locally or at a configured URL.
- A PostgreSQL database named upimesh and credentials with permission to connect and create tables/indexes in its schema. Flyway creates/updates the schema; it does not create the PostgreSQL server or database.
- A demo username and a long, unique demo password, supplied through environment variables.
- Network access on the first Maven Wrapper/dependency download.

The normal application configuration uses PostgreSQL. The H2 configuration lives under src/test/resources/application-test.properties and is used by tests; it is not the normal application runtime database.

## Prepare PostgreSQL

Create the database using a PostgreSQL role allowed to create databases. For example, from PowerShell:

~~~powershell
psql -h localhost -U postgres -d postgres -c "CREATE DATABASE upimesh;"
~~~

If the database already exists, skip this command. The local JDBC URL defaults to jdbc:postgresql://localhost:5432/upimesh. Set a local PostgreSQL username and password in your shell; the application does not contain fallback database credentials.

### Configure and start on Windows PowerShell

~~~powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/upimesh"
$env:SPRING_DATASOURCE_USERNAME = "your-local-postgres-username"
$env:SPRING_DATASOURCE_PASSWORD = "your-local-password"
$env:PAYMESH_DEMO_USERNAME = "your-demo-username"
$env:PAYMESH_DEMO_PASSWORD = "your-long-random-demo-password"
.\mvnw.cmd spring-boot:run
~~~

### Configure and start on macOS/Linux

~~~bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/upimesh'
export SPRING_DATASOURCE_USERNAME='your-local-postgres-username'
export SPRING_DATASOURCE_PASSWORD='your-local-password'
export PAYMESH_DEMO_USERNAME='your-demo-username'
export PAYMESH_DEMO_PASSWORD='your-long-random-demo-password'
./mvnw spring-boot:run
~~~

SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, PAYMESH_DEMO_USERNAME, and PAYMESH_DEMO_PASSWORD are read by application.properties. The URL defaults to the local upimesh address, but database username/password and demo credentials are required; the app fails startup if they are missing. The server listens on port 8080 by default; SERVER_PORT can override that. .env.example is a reference template: Spring Boot does not load a .env file automatically, so set variables in your shell or IDE run configuration. Do not commit a real .env file.

Other supported environment overrides are LOGGING_LEVEL_COM_DEMO_UPIMESH, UPI_MESH_IDEMPOTENCY_TTL_SECONDS, and UPI_MESH_PACKET_MAX_AGE_SECONDS. They control application log verbosity, idempotency retention, and the maximum accepted packet age respectively. The application defaults are INFO, 86400 seconds, and 86400 seconds.

On startup, Flyway applies the existing migrations and the application seeds Alice, Bob, Carol, and Dave only if the account table is empty. Open http://localhost:8080; the browser prompts for the demo username and password before loading the dashboard. It reuses those credentials for the dashboard API calls. Stop the server with Ctrl+C.

## Dashboard demonstration

Use a fresh demo database if you want the initial seeded balances. The dashboard does not need or validate a real PIN; its default 1234 is only demo input.

1. Open the dashboard and wait for the account balances and simulated devices to load.
2. Choose Alice → Bob, leave the amount at 500 and PIN at 1234, then click **Inject into Mesh**. The server-side demo helper builds a payment instruction, hashes the PIN into it, encrypts it with the server's current public key, and places a packet on phone-alice.
3. Click **Run Gossip Round**. The simulator copies packets among all virtual devices and decrements the TTL. The default topology has one internet-connected device, phone-bridge.
4. Click **Bridges Upload to Backend**. The bridge submits its packet; the backend hashes the ciphertext, claims it, decrypts it, checks instruction freshness, and atomically updates the sender/receiver balances, transaction ledger, and idempotency record. The dashboard refreshes balances and ledger every three seconds and logs the flush outcome.
5. Click **Bridges Upload to Backend** again before resetting. The bridge still holds the same packet, so the backend should report it as a duplicate and leave the balances unchanged.

Expected first outcome: SETTLED; expected second outcome: DUPLICATE_DROPPED. A payment with insufficient funds is recorded as REJECTED. Malformed or stale packets can be reported as INVALID.

### Reset behavior

**Reset Mesh + Cache** clears packets from the simulated devices and calls IdempotencyService.clear(), which deletes idempotency records. It does **not** restore account balances, delete transaction history, or reseed demo accounts. The next payment created from the dashboard gets a new packet/ciphertext and can be processed normally, subject to the sender's remaining balance. To restore the original demo data, use a fresh local demo database; do not expect this button to reset the ledger.

## How the payment path works

POST /api/demo/send simulates sender-side packet construction inside the server. MeshSimulatorService performs simplified in-memory gossip. POST /api/mesh/flush sends bridge-held packets concurrently to BridgeIngestionService in-process. The service calls IdempotencyService before decrypting; the database primary key arbitrates first claims. SettlementService locks and checks the claimed record and token, then commits balance changes, the transaction row, and the terminal idempotency state in one transaction.

The default simulator has just one bridge. Repeating flush demonstrates sequential duplicate handling. The existing tests exercise concurrent bridge delivery; the dashboard's default topology does not simulate multiple bridges racing on the same packet.

A fresh RSA key pair is generated each time the application starts. Packets created before a restart cannot be decrypted after that restart.

## HTTP API

All API paths are under /api. JSON request/response examples use the application models; amounts are decimal numbers.

| Method | Path | Purpose |
|---|---|---|
| GET | / | Thymeleaf dashboard. |
| GET | /api/server-key | Base64 public key and crypto scheme metadata. |
| POST | /api/demo/send | Demo request { "senderVpa": "alice@demo", "receiverVpa": "bob@demo", "amount": 500, "pin": "1234", "ttl": 5, "startDevice": "phone-alice" }; returns packet ID, ciphertext preview, TTL, and injection device. ttl and startDevice are optional. |
| GET | /api/mesh/state | Device IDs, connectivity, packet counts/IDs, and idempotency-record count. |
| POST | /api/mesh/gossip | Performs one gossip round; returns transfer count and device counts. |
| POST | /api/mesh/flush | Uploads bridge-held packets; returns upload count and each bridge's outcome, reason, and transaction ID. |
| POST | /api/mesh/reset | Clears mesh packets and idempotency records only. |
| POST | /api/bridge/ingest | Ingests a MeshPacket; optional headers X-Bridge-Node-Id and X-Hop-Count. |
| GET | /api/accounts | Account records and current balances. |
| GET | /api/transactions | Most recent 20 ledger records. |

MeshPacket JSON has packetId, ttl, createdAt (epoch milliseconds), and ciphertext. An ingestion response has outcome, packetHash, reason, and transactionId; for example, a successful result has outcome SETTLED, while a repeated packet returns DUPLICATE_DROPPED.

The local dashboard uses same-origin fetch calls for these endpoints. In public dashboard mode, the browser uses `GET /api/demo/dashboard` for the synthetic read-only view. The detailed API routes require HTTP Basic authentication. Browser writes under `/api` also require a same-origin Origin header, which the browser sends automatically.

## Security and demonstration limits

PayMesh simulates payments and settlement. The production dashboard and its read-only snapshot are available without credentials. The rest of the API and the OpenAPI documentation use the shared HTTP Basic credentials configured by `PAYMESH_DEMO_USERNAME` and `PAYMESH_DEMO_PASSWORD`. Public dashboard mode blocks API writes even with those credentials. When writes are enabled for local simulation, the API requires a same-origin request and applies its in-memory per-client rate limit.

The entered PIN is not verified and provides no authentication. There is no real bank, UPI, NPCI, account provider, phone app, Bluetooth transport, or real settlement. The mesh and accounts are simulated. Local development may use an ephemeral RSA key; the production profile requires a persistent key supplied through the environment.

## Deployment on Render

PayMesh can run as a **Render Free Web Service** backed by **Render Free Postgres** in the same region. The production profile disables demo writes by default.

### Architecture

- One **Render Web Service** runs the Spring Boot JAR inside a Docker image built from the root `Dockerfile`. The image is a two-stage build: a Maven 3.9.9 + JDK 21 builder compiles the executable JAR, and an Eclipse Temurin 21 JRE runtime runs it as a non-root `paymesh` user.
- One **Render Free Postgres** database holds the demo ledger and idempotency records. Select PostgreSQL 16 to match local development and CI.

### Environment variables in the Render Web Service settings

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | yes | Build the JDBC URL from the Render database's internal hostname and database name using `jdbc:postgresql://<internal-host>:5432/<database>?sslmode=require`. |
| `SPRING_DATASOURCE_USERNAME` | yes | Render Postgres username. |
| `SPRING_DATASOURCE_PASSWORD` | yes | Render Postgres password. |
| `PAYMESH_DEMO_USERNAME` | yes | Shared HTTP Basic username for the dashboard and API. |
| `PAYMESH_DEMO_PASSWORD` | yes | Shared HTTP Basic password. Use a long, unique random value. |
| `PAYMESH_RSA_PRIVATE_KEY_BASE64` | yes | Persistent PKCS#8 RSA private key encoded as Base64; store only as a host secret. |
| `PAYMESH_PUBLIC_DASHBOARD` | no | Defaults to `true` in production. Enables anonymous dashboard viewing and blocks all API writes. |
| `PAYMESH_DEMO_WRITES_ENABLED` | no | Defaults to `false` in production. Interactive simulation requires public dashboard mode to be disabled. |
| `PAYMESH_API_WRITE_LIMIT_PER_MINUTE` | no | In-memory per-client write limit; defaults to 30 in production. |
| `PORT` | automatic | Render supplies the HTTP port. The production profile binds to `0.0.0.0`. |

### Render configuration

- Select the Docker runtime and the root `Dockerfile`. Render builds the image and starts it using its existing entrypoint.
- Select the Free plan and set the HTTP health check path to `/health`. The endpoint checks database connectivity.
- Create the Free Postgres database in the web service's region and use its internal hostname. See [Render Postgres connection details](https://render.com/docs/postgresql-creating-connecting#internal-connections).

### Initial migration behaviour

On first startup, Flyway applies the existing migrations in `src/main/resources/db/migration/` against the Render database. The existing startup logic initializes the four demo accounts when the table is empty. Set the demo credentials and persistent RSA key before startup.

### Local development

The local PostgreSQL instructions and the H2 test configuration are unchanged. The Dockerfile is for deployment only and is not used for local development.

## Tests

Run the full automated test suite from the project root:

~~~powershell
.\mvnw.cmd test
~~~

On macOS/Linux use `./mvnw test`. Tests with the test profile use H2 in PostgreSQL compatibility mode. PostgreSqlIntegrationTest uses the default PostgreSQL configuration and requires the configured PostgreSQL database to be available.

## CI and public demo safeguards

`.github/workflows/ci.yml` runs `./mvnw clean verify` on Java 21 with a fresh PostgreSQL 16 service. It creates only a throwaway `paymesh_test` database and does not use `.env` files or deployment credentials. CI does not deploy.

The Docker image activates the `production` profile, runs as a non-root user, requires `PAYMESH_RSA_PRIVATE_KEY_BASE64`, and checks `/health`. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `PAYMESH_DEMO_USERNAME`, `PAYMESH_DEMO_PASSWORD`, and `PAYMESH_RSA_PRIVATE_KEY_BASE64` in the hosting provider's secret settings. Generate an RSA PKCS#8 private key, Base64-encode its DER bytes, and store that value as a secret; never put it in the repository or an image build argument. Production writes default to disabled. `PAYMESH_API_WRITE_LIMIT_PER_MINUTE` adjusts the in-memory per-IP limit and defaults to 30. The limiter resets on restart and is not shared across replicas.

Flyway migrations are versioned under `src/main/resources/db/migration`. The current migrations create tables/indexes and add an idempotency column; they do not drop or truncate tables. Back up persistent data before future migrations and review every migration before applying it to a hosted database. Hibernate is set to `validate`, so schema changes must go through Flyway.

### Free portfolio hosting considerations

The Free Web Service has 512 MB RAM and 0.1 CPU, spins down after 15 minutes without traffic, and uses an ephemeral filesystem. Free Postgres has 1 GB of storage, expires after 30 days, and has no managed backups. To continue the demo after expiry, the owner must manually provision a replacement and update the datasource environment variables. For a zero-cost deployment, use a Hobby workspace without a payment method. Render suspends free services or pauses builds when the applicable monthly quota runs out. See [Render free service limits](https://render.com/docs/free) and [Render billing FAQ](https://render.com/docs/faq).

For public access, use a dedicated empty database, TLS at the host, unique demo credentials and RSA key stored as host secrets, `SPRING_PROFILES_ACTIVE=production`, `PAYMESH_DEMO_WRITES_ENABLED=false`, and health check path `/health`. The app has one shared HTTP Basic identity and a process-local write limiter, so public write mode should remain disabled absent additional abuse controls.
