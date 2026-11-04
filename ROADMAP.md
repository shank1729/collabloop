# Product improvement checklist

Work through these in order, checking existing behavior before adding features.

- [x] Auth: bounded requests, slow-response feedback, actionable errors, retry,
  accessible status/alerts, and frontend regression tests.
- [x] Dashboard: searchable/sortable document table, ownership filters, dates,
  owner/collaborators, refresh, explicit creation, and empty states.
- [x] Rename/delete: enforce owner/editor rename and owner-only delete; return 403
  for forbidden actions, validate titles, guard pending requests, and test roles.
- [x] Sharing: owner-only role controls/removal, pending/error states, private
  access notifications after commit, and WebSocket authorization on subscribe/delivery.
- [x] Presence: explicit leave, heartbeat/expiry, multi-tab deduplication, typing
  timeout/throttle, reconnection cleanup, and initials with readable status.
- [x] History: read-only snapshot previews, return to live content, independent
  loading/error/timeout/refresh, corrected snapshot metadata, and access tests.
- [x] Deployment: local/production env references, recorded hosting URLs, Render
  settings, health/smoke checks, redeploy/rollback guide, and Docker context ignore.
- [x] Production: required JWT secret, production example-key checks, exact shared
  CORS allowlist, open-in-view off with explicit fetch plans, and consistent 401s.
- [x] Testing: frontend regression coverage, real two-user HTTP/SockJS workflow,
  CI verification/build jobs, and a repeatable release checklist.
- [x] Auth rate limiting: bounded process-local windows, JSON 429/retry guidance,
  safe proxy defaults, configuration docs, and concurrency/HTTP regression tests.
- [ ] Deployment follow-up: verify Render's proxy chain and client-IP mapping
  before enabling forwarded-header trust; confirm independent hosted allowances.
- [x] WebSocket JWT expiry: verified connection deadlines, incoming/outgoing queue
  checks, idle socket closure, presence cleanup, and sign-in/reconnect UX.
- [ ] Follow-up: assess refresh tokens; use a shared limiter before scaling to
  multiple backend instances.

## Auth concepts

`fetch()` has no application-level deadline by default. An `AbortController`
cancels the request after 60 seconds, including reading its response body. A
`finally` block always clears the timer; the form also uses `finally` to restore
its controls on success or failure.

HTTP errors (such as 401 or 503) are different from network failures, where the
browser cannot obtain a response at all. CORS failures also appear as network
failures to JavaScript. Neither one proves that a backend is asleep, so the UI
describes slow startup as a possibility.

Cancelling a client request does not roll back server work. Registration may
already have created the account when the connection times out. The error asks
the user to try signing in first; requests are never automatically retried.

## Dashboard concepts

The document list is the source of truth. Search, ownership filters, and sorting
derive a visible list from it; they do not change the underlying array. These
filters are for browsing only. The backend still enforces document permissions.

Reading an empty workspace no longer creates a document. Creation happens only
after a user action, and deleting the last document returns to an empty dashboard.
Opening a document loads its details; a stale response from a previously selected
document is ignored so it cannot overwrite the current selection.

## Rename/delete concepts

The frontend controls which actions are available, while the backend enforces
authorization for every request. A collaborator with insufficient privileges gets
403 Forbidden; a user without document access gets 404 so private documents stay
hidden. Invalid input gets 400 Bad Request.

PATCH changes only the supplied fields, so renaming preserves document content.
Renames are trimmed and validated. Delete runs in a transaction that removes
document access and history with the document. Pending actions lock conflicting
controls, and failed requests restore them so the user can retry.

## Sharing concepts

The owner grants a role (VIEWER or EDITOR) to a registered user. Changing a role
updates the existing access record. Removing access uses JPA orphan removal to
delete that record; the document and its content remain intact.

After a successful transaction commits, a private `/user/queue/document-access`
notification updates the affected user's controls or closes a revoked document.
Rollback sends no notification. REST and edit handlers enforce current access
even if a client misses the notification.

WebSocket authentication identifies the user; authorization decides which
documents that user can receive. Access is checked when subscribing and on each
document/presence delivery, so an old subscription cannot continue receiving
updates after revocation. Clients cannot publish directly to broker topics or
private queues. Checking the database per recipient favors immediate revocation
for this small app; higher traffic would call for a permission cache with explicit
invalidation.

## Presence concepts

Presence is temporary state, kept in memory for this single backend instance.
Each socket session belongs to at most one document. A person may have several
tabs, but appears once; typing in any tab takes precedence over viewing.

The client sends a heartbeat every 15 seconds. The server expires a session after
45 seconds without activity, so a lost connection cannot leave someone listed
forever. Typing stops after two seconds of client inactivity, with a four-second
server fallback. Heartbeats do not extend typing status. A scheduled sweep runs
every second and broadcasts only changed snapshots.

Navigation sends an explicit leave. Disconnect and revocation also remove
presence; a delayed leave for an old document cannot remove a newer selection.
Messages preserve receive/publish order within each STOMP session. Reconnection
creates a fresh SockJS transport and the editor rejoins its document. Timers and
old subscriptions are cleared when leaving or disconnecting.

Tests advance an injected clock instead of sleeping. A deployment with multiple
backend instances would need shared presence storage and a shared message broker.

## Version history concepts

A snapshot stores the document content at a saved point in time. Previewing it
does not update the live document. The live content remains in separate React
state and continues receiving updates while a preview is open. Returning to live
content therefore shows the current document, not the selected snapshot.

The backend flushes document writes before capturing version and timestamp
metadata. Flush sends SQL within the transaction; commit makes the transaction
final. The document and its snapshot are saved in the same transaction. History
is sorted by timestamp, then snapshot ID to break ties deterministically.

Snapshot IDs uniquely identify entries. Document version numbers are optimistic
locking metadata, so they need not form a consecutive history counter. Older
stored snapshots are preserved as-is; fixing future metadata does not reconstruct
past version numbers or times. Snapshots currently contain content, not old titles.

History loads independently from the editor, has a 60-second request deadline,
and ignores responses from a document that has been left. Live changes mark the
list as needing refresh; full snapshot content is not downloaded on each keystroke.
Owners, editors and viewers can inspect history while they have document access.
This step provides previews; restoring an old version is a separate future feature.

## Deployment concepts

Frontend environment values are baked into the public JavaScript bundle at build
time; backend values are read when the process starts. Root env templates are not
automatically read by Spring, so the shell, Eclipse, Docker or host must supply
the variables explicitly.

The committed source, lockfile and configuration reference let another developer
repeat a build. `npm ci` uses the lockfile rather than resolving new versions.
Deployment adds the runtime configuration and starts the built application.
Changing source locally does not change a hosted release.

Health checks verify application readiness, including database connectivity.
Smoke checks verify user workflows across the frontend, API, database and socket.
Rolling back code does not roll back a database migration.

## Production configuration concepts

JWT signatures depend on a secret held only by the backend. Missing/short keys
and placeholders fail at startup. Docker defaults to the `prod` profile, which
also rejects the documented development keys. Validation catches known mistakes;
it cannot measure how randomly an arbitrary key was generated. Rotation invalidates
old tokens and requires sign-in again.

CORS is a browser policy, not authorization. REST and SockJS share one validated
list of exact origins. The old per-controller localhost exception is removed.
Cookies/credentialed SockJS transports can still use these specific origins.

With open-in-view disabled, database relationships used by response DTOs are
loaded deliberately with fetch joins. Authorization uses an `exists` subquery
so fetching a collaborator's document does not accidentally hide other
collaborators from the returned collection. Tests without a surrounding test
transaction exercise serialization after the service transaction has ended.

401 means authentication is missing or invalid; 403 means the identified user
does not have permission. JWT parsing and unknown-user failures now consistently
produce JSON 401 responses, allowing the frontend to clear an expired session.
Malformed request JSON returns 400. SQL logging is off by default.

Auth rate limiting and active WebSocket expiry are implemented as the follow-ups
described below. Refresh-token support remains planned.

## Testing concepts

Unit tests isolate a rule, while integration tests check how components work
together. The two-user socket test exercises HTTP authentication, the real
STOMP connection, database permission queries and outgoing message delivery.
It found an implicit join that denied the owner updates whenever the document
had no collaborators. The query now checks ownership or an existing access row.

Tests wait for expected messages with deadlines and close their temporary server
and clients afterward. H2 keeps test data disposable; the test profile also
prevents cloud URL conversion from overriding it. H2 does not replace testing
against PostgreSQL or checking the hosted environment.

Continuous integration repeats tests and builds on GitHub for pushed changes.
It helps catch regressions, meaning previously working behavior that a new
change breaks. It is separate from deployment. The workflow and release guide
are ready locally; a successful GitHub run and hosted smoke checks still need
to be confirmed after publishing. See TESTING.md for commands and limitations.

## Authentication rate limiting concepts

An MVC interceptor checks an allowance before authentication request parsing and
password hashing. Login and registration share 20 attempts per IP in a window
that starts with the first request and lasts 60 seconds by default. After that,
HTTP 429 and Retry-After tell the client when to try again. Successes and failures
both count; retries do not extend the wait. The form shows the wait without
automatically repeating a request.

A synchronized check makes counting atomic across request threads. A monotonic
timer measures elapsed time, so wall-clock adjustments do not reset allowances.
Expired windows are discarded; a maximum client count bounds memory. Full storage
rejects new addresses instead of evicting live budgets. Fixed windows are simple,
but allow bursts at boundaries; this is not a distributed brute-force defense.

IP identity has a trust boundary. Direct connections use their socket address;
headers supplied by a caller are ignored by default. A verified proxy can supply
the visitor address through Tomcat's native forwarding support, but only with
an explicit trusted-proxy configuration. Until verified on Render, the proxy's
address may group users into one budget. NAT also groups users. This is documented
as deployment follow-up rather than assuming an arbitrary header is trustworthy.

Tests advance an injected timer and send concurrent requests without sleeps.
HTTP tests check shared allowances, malformed payloads, CORS retry headers,
preflight exclusions and forged forwarding headers. State currently belongs to
one backend process and resets on restart; multiple instances need shared storage.

## WebSocket authentication lifetime concepts

Authentication at CONNECT establishes an identity, but the connection may stay
open beyond that token's expiry. The backend now binds each transport session to
the expiry read from its verified JWT. It checks that deadline on SEND/SUBSCRIBE
and outgoing application messages, including private access notifications.
Queued messages are checked again before handling so time waiting for a worker
does not extend the authentication lifetime. A second CONNECT cannot renew an
existing transport session.

A scheduled sweep closes expired idle sockets, normally within about one second,
using close code 4001. Existing disconnect handling removes document presence
and subscription authorization state. Message checks do not wait for the sweep.
Work already accepted before the deadline can still complete; expiry does not
roll back saved edits. Signing in again issues a fresh token.

The browser decodes the exp claim only to schedule UI feedback. Decoding does not
verify a signature and grants no authority: the backend remains responsible for
authentication. Expiry stops reconnects, clears presence timers and returns to
sign-in. Reconnecting keeps the same absolute deadline. Before-connect and publish
checks also handle suspended browser timers; long lifetimes use timer chunks to
avoid JavaScript timeout overflow. A server expiry close is honored even if the
browser clock is behind. Old socket callbacks and token-specific expiry events
cannot clear a newer login stored by another tab.

Tests use controlled clocks for expiry boundaries and a real two-user SockJS flow
to verify idle closure, presence cleanup and an unaffected owner's edits. The
browser tests cover normal network reconnects, expired tokens, timer suspension,
cleanup and the sign-in message. Refresh tokens remain a separate design decision.
