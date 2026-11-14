# Testing and release checks

Run these checks before publishing a change. They build and test the code; they
do not deploy it or start the normal development servers.

## Local commands

Use Java 17 and Node.js 24. From the repository root in PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Then in a separate terminal:

```powershell
cd frontend
npm ci
$env:CI = 'true'
npm test -- --watchAll=false --runInBand
npm run build
```

On Linux/macOS, use `sh ./mvnw --batch-mode --no-transfer-progress verify` and
prefix the frontend test/build commands with `CI=true` instead of setting a
PowerShell environment variable. `npm ci` installs the committed lockfile and
replaces `node_modules`; stop a running frontend dev server before reinstalling.

The backend integration tests activate the `test` profile and use disposable H2
databases with Flyway migrations. The cloud URL converter ignores `DATABASE_URL`
and `DB_URL` under this profile. Use a clean test terminal: remove custom
`SPRING_DATASOURCE_*`, `SPRING_CONFIG_*` or JVM property overrides that could
replace test configuration. No real database credentials are required.

## What the checks prove

| Layer | Coverage |
| --- | --- |
| Backend unit tests | Signing-key validation, origins, message authorization, access events and presence expiry using a controlled clock |
| Auth rate-limit tests | Window expiry, atomic concurrent counting, bounded storage, settings validation, HTTP/CORS retry responses and real-server proxy-header spoofing |
| WebSocket expiry tests | Exact JWT deadlines, queued incoming/outgoing frames, private updates, cleanup, close retry, and a real idle-expiry/presence flow with an advanced test clock |
| Backend integration tests | Authentication, permissions, CRUD, sharing, history and response mapping with open-in-view disabled |
| Real HTTP + SockJS/STOMP test | Register/sign in, create/share, two-way edits, typing/leave, role changes, revoke, history and delete |
| Frontend tests | Authentication errors/timeouts, document interactions, sharing, presence, history and socket lifecycle |
| Production build | Frontend compilation and backend executable JAR packaging |

`CollaborationFlowTest` starts an embedded server on a random port and connects
two authenticated clients over real sockets. Its data lives in a separate H2
database; connections and the server close after the test. It waits for expected
messages with deadlines instead of guessing how long background work takes.
After access removal, it deliberately leaves the old subscription open and
checks that it receives no further content during a bounded observation window.
Authorization unit tests also check the outgoing-message policy directly.

`WebSocketExpiryFlowTest` connects two real SockJS/STOMP clients and advances only
the expiry service's test clock. The scheduled sweep closes the expired editor,
presence drops to one person, and the still-valid owner continues editing. Unit
tests check that message guards deny access at the deadline before the sweep.
Frontend tests cover reconnect lifetime, expiry close codes, timer suspension,
long timeout chunks, newer-login protection and the sign-in message. The wire
tests use the WebSocket transport; deployed HTTP fallback transports need smoke
checks too.

The flow covers owner delivery both before sharing and after the last
collaborator is removed. This caught a query bug: an implicit inner join on the
collaborator's user excluded documents with no collaborators. The permission
query now checks ownership or the existence of a matching access record.

H2's PostgreSQL compatibility mode is useful for fast checks, but does not prove
all PostgreSQL behavior. These checks also do not measure load, guarantee delivery
during every possible network failure, or test the deployed browser/Render/Neon
combination. A PostgreSQL integration job and browser automation remain useful
follow-ups. The editor still has no CRDT/operational-transform conflict resolution.

## GitHub Actions

`.github/workflows/ci.yml` runs on pushes and pull requests targeting `main`, and
can be started manually. Independent jobs verify the backend and install/test/build
the frontend. A third job builds the backend and Oracle frontend-gateway images
after both verification jobs, validates Compose with dummy settings, and validates
Caddy configuration without starting the application or requesting certificates.
No hosting credentials are required, and the workflow does not publish an image
or deploy either service. The Docker check verifies image creation, not runtime
connectivity to PostgreSQL.

The workflow becomes available after it is committed and pushed. Review the
actual run on GitHub before calling CI successful. It does not configure Render's
auto-deploy policy; check that policy before pushing to the deployment branch.

## Release checklist

- [ ] Review the diff and run the local checks above.
- [ ] Check backend production configuration, including a generated `JWT_SECRET`
  and the exact frontend origin. Production rejects the example signing keys.
- [ ] Review the auth rate-limit policy. Verify proxy trust/client-IP mapping
  before enabling forwarded-header processing; default proxy budgets may be shared.
- [ ] Commit/push the intended changes and inspect all three GitHub Actions jobs.
- [ ] Deploy matching backend/frontend commits using [DEPLOYMENT.md](DEPLOYMENT.md).
- [ ] Confirm backend health and the deployed commit in each Render service.
- [ ] In two browser profiles, verify sign-in and create/share a disposable document.
- [ ] Edit from both profiles; verify presence and read-only history previews.
- [ ] Verify short-lived JWT expiry in a local/staging session: sign-in message,
  stopped reconnects, presence cleanup and an unaffected still-valid session.
- [ ] Downgrade to viewer, promote to editor, then remove access while connected.
  Confirm permissions change and the removed user stops receiving updates.
- [ ] Rename/delete as owner; refresh and verify the resulting dashboard state.

Passing tests gives evidence for the covered behavior. The hosted smoke checks
verify the environment settings and infrastructure that local tests cannot see.

## Text synchronization regressions

The editor saves text through `PUT /documents/{id}/content` with a base version
and a unique edit ID. WebSocket broadcasts carry committed snapshots. The server
rejects stale writes with HTTP 409; the client merges its pending edits before
retrying. Repeating an accepted edit ID returns its original acknowledgement.

The automated checks include delayed acknowledgements while typing, two writers
saving simultaneously, out-of-order snapshots, a lost response, draft recovery,
page caret position, and IME input. `ContentSynchronizationTest` checks concurrent
HTTP requests, stale-write rejection, idempotency, viewer permissions and clearing
a document. It can also run against PostgreSQL via `collab.test.database-url`
with the PostgreSQL datasource driver, dialect and credentials overridden.

For the hosted smoke check, refresh both browsers, open the same disposable shared
document as owner and editor, and type in both. Keep typing while saves complete;
verify that both drafts converge and remain intact after reloading. Repeat with
network throttling and a brief disconnection, then remove the disposable document.
