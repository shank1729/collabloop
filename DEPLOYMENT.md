# Deployment Guide

The repository contains a Java 17 Spring Boot backend at the root and a Create
React App frontend in `frontend/`. The documented deployment uses a Render
Docker Web Service, a Render Static Site, and Neon PostgreSQL.

The app also runs on an Oracle Always Free VM. That setup serves both app
components behind Caddy HTTPS and reuses Neon. See
[deploy/oracle/README.md](deploy/oracle/README.md) for the live address and
instructions for publishing an update.

## Recorded Deployment

These addresses were configured during the initial deployment. Check the Render
dashboard for service status and the deployed commit; local changes are not live
until they are committed, pushed, and deployed.

| Component | Address / location |
| --- | --- |
| Frontend | [realtime-collab-frontend.onrender.com](https://realtime-collab-frontend.onrender.com) |
| Backend | [realtime-collab-backend-6bbk.onrender.com](https://realtime-collab-backend-6bbk.onrender.com) |
| Health | [Backend health](https://realtime-collab-backend-6bbk.onrender.com/actuator/health) |
| PostgreSQL | Neon project; credentials belong only in backend environment settings |
| Source | [shank1729/collabloop](https://github.com/shank1729/collabloop) |

The app currently uses one backend instance. Live presence and the simple STOMP
broker are in memory; adding backend instances requires shared presence storage
and a shared broker.

Before each release, follow [TESTING.md](TESTING.md) for repeatable local checks,
GitHub Actions coverage and the two-browser hosted smoke checklist. CI does not
deploy services or change Render's auto-deploy settings.

## Configuration Files

| File | Purpose | How it is loaded |
| --- | --- | --- |
| `.env.example` | Local backend configuration reference | Set these variables in PowerShell or Eclipse |
| `.env.production.example` | Backend production reference | Enter these values in Render's backend environment |
| `frontend/.env.example` | Local frontend URL reference | Copy to `frontend/.env.local` if overriding defaults |
| `frontend/.env.production.example` | Frontend production URL reference | Enter in Render's Static Site environment before building |

Spring Boot does **not** automatically load a root `.env` file in this project.
Copying the backend example alone does not configure Eclipse or Maven.
Docker's `--env-file` option can explicitly load one for a container.

React's `REACT_APP_*` values are public and are embedded in the JavaScript bundle.
Never put database credentials or `JWT_SECRET` in any frontend variable.
Changing frontend variables requires a rebuild. Changing backend variables
requires restarting/redeploying the backend.

## Run Locally on Windows

Prerequisites: Java 17, PostgreSQL, Node.js/npm, and Git. Maven is provided by the
wrapper. Use the same Node major version locally and on Render; set Render's
`NODE_VERSION` explicitly if you need to match your local environment.

1. Start the local PostgreSQL service. To discover its name, run
   `Get-Service *postgres*`. For this machine the service is `postgresql-x64-17`;
   starting a stopped service requires an administrator PowerShell:

   ```powershell
   Start-Service postgresql-x64-17
   ```

2. Create an empty database once, if it does not already exist:

   ```powershell
   createdb -U postgres collabdb
   ```

3. In a regular PowerShell at the repository root, configure and start the backend:

   ```powershell
   Remove-Item Env:DATABASE_URL -ErrorAction SilentlyContinue
   $env:DB_URL="jdbc:postgresql://localhost:5432/collabdb"
   $env:DB_USERNAME="postgres"
   $env:DB_PASSWORD="your-local-postgres-password"
   $env:JWT_SECRET="local-development-only-secret-at-least-32-bytes"
   $env:JWT_EXPIRATION_MS="86400000"
   $env:CORS_ALLOWED_ORIGINS="http://localhost:3000"
   $env:PORT="8080"
   .\mvnw.cmd spring-boot:run
   ```

   In Eclipse, use **Run Configurations > Java Application >
   CollabBackendApplication > Environment** to set the same variables, then run
   the application. Variables set in a separate terminal do not modify an
   already-running Eclipse process.

4. In another terminal, from the repository root:

   ```powershell
   cd frontend
   npm ci
   npm start
   ```

The UI is at [localhost:3000](http://localhost:3000), and the backend is at
[localhost:8080](http://localhost:8080). Frontend defaults already target the local
backend. If needed, copy `frontend/.env.example` to `frontend/.env.local`, edit
it, and restart the frontend dev server.

Flyway runs schema migrations at backend startup; Hibernate validates the schema.
Do not change `ddl-auto` to `create` to work around a startup error.

## Backend Environment

| Variable | Value / behavior |
| --- | --- |
| `DATABASE_URL` | Cloud PostgreSQL URL with credentials and TLS query, such as Neon's connection string |
| `DB_URL` | Alternative JDBC URL, e.g. `jdbc:postgresql://localhost:5432/collabdb` |
| `DB_USERNAME`, `DB_PASSWORD` | Used with the JDBC form |
| `JWT_SECRET` | Unique random signing secret, at least 32 UTF-8 bytes for HS256 |
| `SPRING_PROFILES_ACTIVE` | `prod` for production; the Docker image sets this by default |
| `JWT_EXPIRATION_MS` | Token lifetime in milliseconds; `86400000` is 24 hours |
| `CORS_ALLOWED_ORIGINS` | Comma-separated frontend origins; scheme + host + optional port, no trailing slash or path |
| `PORT` | Local default `8080`; Render supplies its own runtime port |
| `AUTH_RATE_LIMIT_REQUESTS` | Shared login/registration allowance per client IP per window; default `20`, range 1-1000 |
| `AUTH_RATE_LIMIT_WINDOW_SECONDS` | Window duration; default `60`, range 1-3600 |
| `AUTH_RATE_LIMIT_MAX_CLIENTS` | Maximum in-memory client windows; default `10000`, range 1-100000 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Default `none`; only use `native` with a verified proxy trust configuration below |
| `AUTH_TRUSTED_PROXIES` | Java regex matching verified immediate/intermediate proxy IPs for Tomcat; default `(?!)` matches no addresses |

Choose either the cloud URL or JDBC variables. A nonblank `DATABASE_URL` takes
precedence over `DB_URL`. Remove a stale cloud variable before switching to the
local database.

Cloud URLs beginning with `postgres://` or `postgresql://` are converted to JDBC
settings by `DatabaseUrlEnvironmentPostProcessor`. Credentials come from the URL;
TLS parameters such as `sslmode=require` are retained, while `channel_binding`
is removed for this JDBC connection path. Keep special characters URL-encoded;
copy the provider's connection string instead of assembling credentials manually.

To generate a production signing secret in PowerShell:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtGenerator.GetBytes($jwtBytes)
$jwtGenerator.Dispose()
[Convert]::ToBase64String($jwtBytes)
```

Enter the resulting value only in the backend environment. Keep it stable across
normal redeploys; changing it invalidates existing tokens and requires users to
sign in again. Never use an example secret in production.

The backend now refuses to start without `JWT_SECRET`; there is no implicit
development fallback. The `prod` profile also rejects the documented local/test
keys and known demo/placeholder patterns. Before deploying these changes, set a
generated value in Render's backend environment. In Eclipse, set `JWT_SECRET` in
the run configuration before restarting. Length checks cannot prove randomness;
use the generator above rather than choosing a human-readable phrase.

## Authentication Rate Limits and Proxies

POST requests to `/auth/login` and `/auth/register` share a fixed window of 20
attempts per client IP for 60 seconds by default. The first attempt starts the
window. Successful logins, failed credentials, invalid input and malformed JSON
all consume allowance when they reach the MVC handler. Security/CORS rejections
that occur before MVC do not. Preflight OPTIONS and document/WebSocket routes
are outside this limiter.

Excess requests receive JSON `429 Too Many Requests` and a `Retry-After` delta in
seconds, rounded up. CORS exposes that header to the frontend, which shows the
wait without automatically repeating an authentication request. Rejected attempts
do not prolong a window. Invalid policy values fail at startup.

The limiter uses `HttpServletRequest.getRemoteAddr()`. Forwarded-header processing
defaults to `none`, so a caller cannot gain new allowances just by forging
`X-Forwarded-For` or `Forwarded`. With a reverse proxy, the connection address may
identify the proxy instead of the visitor. **Until the proxy trust configuration
is verified, Render users may share a proxy's allowance.** Users on the same NAT
address also share a budget. This is a safe fallback, not verified per-visitor
identification on the hosted deployment.

To enable proxy-derived client IPs, first establish the exact proxy chain and
ensure the boundary proxy strips untrusted forwarding values or appends a reliable
source address. Then set `SERVER_FORWARD_HEADERS_STRATEGY=native` and configure
`AUTH_TRUSTED_PROXIES` to match only the verified proxy addresses. This configures
Tomcat's `internal-proxies` setting; the app does not parse the header itself.
Do not use an empty regex, `.*`, or broad private-network trust without verifying
who can reach the backend. Do not select `framework` as a shortcut: the limiter
relies on the container enforcing the proxy boundary. A move to an Oracle VM
also needs a fresh proxy/network review.

The repository cannot infer Render's trusted proxy ranges from the public service
URL. Verify the platform's current forwarding behavior and network boundary
before changing these settings. Afterward, check from two networks that each has
an independent allowance and that adding forged forwarding values cannot reset
one exhausted allowance.
[Spring Boot proxy configuration](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html)
and [Spring Security forwarding-header guidance](https://www.springframework.org/spring-security/reference/features/exploits/http.html).

State is in memory for one backend process and resets on restart. Expired entries
are removed on subsequent auth attempts. At the client cap, new addresses receive
429 until a slot expires; active records are not evicted to make bypass possible.
A shared Redis or edge limiter is needed before scaling to multiple instances.
This policy reduces request bursts; it does not prevent distributed guessing,
replace password policy/MFA, or guarantee protection against denial of service.
Fixed windows also permit bursts around window boundaries.

## WebSocket Authentication Lifetime

Each authenticated connection is bound to the `exp` claim of its verified JWT.
Edits, subscriptions and outgoing application messages are checked against that
deadline, including when queued work reaches a handler. A scheduled sweep closes
expired idle connections with code `4001` (Authentication expired), normally within
about one second. Existing disconnect handling removes presence. In-flight work
accepted before expiry can still finish; saved data is not rolled back.

The frontend also reads the token deadline for UI timing, stops reconnecting on
expiry and returns to sign-in with an explanation. Server checks enforce access
independently of browser timers or clock settings. Normal network loss still
reconnects while the token is valid, using the same deadline. An old connection's
expiry event preserves a newer token stored by another tab. Signing in issues a
fresh token; refresh-token support remains planned.

No new deployment setting is required. `JWT_EXPIRATION_MS` remains the lifetime
for newly issued tokens (default 24 hours); changing it does not change an existing
token's exp claim. Backend and frontend must both deploy this change for the full
UX. Keep the host clock synchronized because JWT expiry is an absolute timestamp.

For a local smoke check, temporarily use `JWT_EXPIRATION_MS=60000`, restart the
backend and sign in to obtain a fresh token. Open a disposable document and wait
about a minute. Verify sign-in appears with the session-expired message, reconnects
stop and the user disappears from presence in a still-valid second session.
Restore the normal lifetime afterward. Test normal WebSocket and any SockJS HTTP
fallback transport you intend to support through the deployed proxy.
[Spring WebSocket transport decorators](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/socket/config/annotation/WebSocketTransportRegistration.html)
provide the connection hook used to close these sessions.

## Neon PostgreSQL

For a fresh setup, create a Neon project and obtain its connection string from
**Connect**. This guide uses a direct connection for the backend and Flyway.

```dotenv
DATABASE_URL=postgresql://USER:URL_ENCODED_PASSWORD@NEON_HOST/DATABASE?sslmode=require
```

Add the real value to Render's backend environment. Do not put it in Git, frontend
settings, or screenshots. Flyway creates the schema in an empty database on first
startup. Reuse the existing Neon database when updating this deployment.
[Neon connection documentation](https://neon.com/docs/connect/connect-from-any-app)

Use the provider's dashboard to check current plan limits and backup/restore
options. Do not rely on a free plan as the only copy of important data.

## Render Backend Settings

For an existing service, review its settings rather than creating a duplicate.
For a new service, connect this repository and select a Docker Web Service.

| Setting | Value |
| --- | --- |
| Branch | `main` (or your intended release branch) |
| Root directory | Empty / repository root |
| Runtime | Docker |
| Dockerfile path | `./Dockerfile` |
| Health-check path | `/actuator/health` |

The Dockerfile builds with Java 17 and the Maven wrapper, then starts the packaged
JAR in a Java 17 runtime image. It skips test execution during image creation, so
run the checks below before deploying. The Docker context excludes frontend
dependencies, Git metadata, local env files, and build output via `.dockerignore`.

Set the backend variables from `.env.production.example` using real values.
Do not override Render's `PORT` with the local example.

For the recorded frontend, this is the production CORS value:

```dotenv
CORS_ALLOWED_ORIGINS=https://realtime-collab-frontend.onrender.com
```

Add `,http://localhost:3000` only if local frontend access to the hosted backend
is intentional. The same configured origins apply to the SockJS endpoint.
Origin configuration is validated at startup: wildcards, paths, trailing slashes,
credentials in URLs, and empty entries are rejected. Keep a finite allowlist.

A successful startup includes `Started CollabBackendApplication`. The health
endpoint should return HTTP 200 with `status: UP`. It checks application health,
including database connectivity; an unauthorized response on a protected
document URL alone does not prove database health.
[Render health-check documentation](https://render.com/docs/health-checks)

## Render Frontend Settings

Create or update a Static Site from the same repository.

| Setting | Value |
| --- | --- |
| Branch | Same intended release branch |
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Publish directory | `build` |

`npm ci` installs from the committed lockfile and fails when it disagrees with
`package.json`. Avoid updating dependencies as part of an unrelated deployment.

For the recorded backend:

```dotenv
REACT_APP_API_BASE_URL=https://realtime-collab-backend-6bbk.onrender.com
REACT_APP_WS_URL=https://realtime-collab-backend-6bbk.onrender.com/ws
```

The API base is the backend origin, without `/api` or a trailing slash. Routes are
`/auth/*` and `/documents/*`. SockJS expects the HTTPS `/ws` URL here, not a
manually substituted `wss://` URL.

The current UI uses in-app state on `/`, not a client-side URL router. If URL-based
routing is added later, configure a Render rewrite from `/*` to `/index.html`.
[Render React deployment documentation](https://render.com/docs/deploy-create-react-app)

## Check and Redeploy

1. From the repository root, run `.\mvnw.cmd --batch-mode --no-transfer-progress verify`.
2. In `frontend/`, run `npm ci`, set `CI=true` as described in TESTING.md, then
   `npm test -- --watchAll=false --runInBand` and `npm run build`.
3. Review `git status` and the diff. Commit only the intended files, then push
   the release branch to GitHub. Inspect the GitHub Actions checks and Render's
   auto-deploy policy; pushing to its deployment branch may start a deploy.
4. In Render, confirm the backend and frontend deploy the intended commit.
   If auto-deploy is disabled, trigger a deploy of that commit manually.
5. Wait for the backend health check and frontend build to pass. Frontend and
   backend deploy independently; allow both to finish.
6. Run the smoke checks below. Updating these local files does not publish them.

Backend-only changes need a backend redeploy. Frontend-only changes or changed
`REACT_APP_*` values need a frontend rebuild/deploy. Backend environment changes
need a backend restart/redeploy. If both APIs and UI changed, deploy both.

## Smoke Checks

- Open the frontend and register a disposable test account, then sign out/in.
- Create a document, rename it, and verify it appears correctly in the dashboard.
- In a separate browser profile or incognito window, sign in as another user.
- Share viewer access, verify read-only content, then promote to editor.
- Edit from both sessions and check synchronization and typing/viewing presence.
- Switch documents and return to the dashboard; confirm old presence disappears.
- Preview history and return to live content without changing the document.
- Downgrade or remove the second user's access while the document is open.
- Delete a disposable document as its owner and verify it disappears.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Local `localhost:5432 refused` | PostgreSQL service is running and the JDBC host/port are correct |
| Local startup unexpectedly uses Neon | Clear `DATABASE_URL` in the launching shell or Eclipse run configuration |
| Health is DOWN / backend fails at startup | Inspect backend logs for database, migration, or signing-key errors |
| Startup says JWT_SECRET is required or an example key is rejected | Set a generated signing key in the launching environment; production rejects local/test examples |
| Startup rejects CORS_ALLOWED_ORIGINS | Use exact HTTP(S) origins separated by commas, without paths, slashes at the end, wildcards or empty entries |
| Register/sign-in times out | Inspect backend status and the browser Network panel for pending requests; startup can be slow on limited compute |
| Browser reports CORS failure | Exact frontend origin is allowed; backend has restarted since env changes |
| UI requests localhost after deploy | Correct Static Site build-time env values and rebuild |
| API requests return 404 | API base has no `/api`; use `/auth/login` and `/documents` |
| REST works but live edits do not | Verify the HTTPS `/ws` URL, origin, authentication, and WebSocket frames |
| Removed users still see old UI controls | Restart the backend, rebuild the frontend, and refresh both sessions to load the matching release |
| Changes are missing | Compare Render's deployed commit with GitHub and the local commit; refresh the frontend |
| Dependency install fails on lock mismatch | Update the lockfile deliberately during development, review it, and commit it with `package.json` |

## Rollback

Redeploy a previously working commit for both services if needed. Keep the same
database and secrets unless a deliberate change is required. A code rollback
does not roll back Flyway migrations or database contents; review migration
compatibility before rolling back code that changes the schema. Never delete
the database as a deployment troubleshooting step.

## Optional Local Docker Check

With Docker installed and running, from the repository root:

```powershell
docker build -t realtime-collab-backend .
docker run --rm --env-file .env -p 8080:8080 realtime-collab-backend
```

For this command, explicitly create an ignored root `.env` with real configuration
based on the relevant backend example. Inside a Docker Desktop container,
`localhost` refers to the container: use `host.docker.internal` to reach a
PostgreSQL server on the Windows host, or use the Neon cloud URL.

The container defaults to the `prod` profile, so its env file must contain a
generated signing secret rather than the local example key. Open-in-view and SQL
logging are disabled. The regression tests include HTTP requests outside test
transactions to check that response mapping does not rely on lazy database reads
after service methods return. Auth rate limiting is process-local; review the
proxy trust and scaling limitations above before production configuration changes.
