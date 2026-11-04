# Realtime Collab Frontend

React frontend for a JWT-protected realtime document collaboration app.

## Features

- Sign in and register through the backend auth API.
- Store the current JWT session in local storage.
- Load, create, share, delete, and edit documents through authenticated API calls.
- Connect to the document WebSocket after sign-in.
- Display document access roles for owners, editors, and viewers.
- Keep viewer access read-only in the UI while the backend remains responsible for security.
- Show active document presence, including who is viewing and who is typing.
- Browse/search/filter/sort documents with ownership and edit dates.
- Let owners change collaborator roles or remove access, with private live notifications.
- Preview saved content snapshots while retaining the live document separately.

## Backend Contract

The frontend expects the backend at `REACT_APP_API_BASE_URL`, defaulting to `http://localhost:8080`.

Shared documents return collaborators shaped like:

```json
{
  "id": 1,
  "name": "Teammate",
  "email": "teammate@example.com",
  "role": "EDITOR"
}
```

Sharing a document sends:

```json
{
  "email": "teammate@example.com",
  "role": "VIEWER"
}
```

Supported collaborator roles are `EDITOR` and `VIEWER`.

Document presence is delivered over STOMP after the authenticated WebSocket connects:

- Subscribe to `/topic/documents/{documentId}` for document updates.
- Subscribe to `/topic/documents/{documentId}/presence` for active-user presence.
- Send joins to `/app/documents/{documentId}/presence/join`.
- Send editable-user typing updates to `/app/documents/{documentId}/presence/typing`.
- Send viewing updates to `/app/documents/{documentId}/presence/viewing`.
- Send heartbeats to `/app/documents/{documentId}/presence/heartbeat` every 15 seconds.
- Send leaves to `/app/documents/{documentId}/presence/leave` when navigating away.
- Subscribe to `/user/queue/document-access` for private access changes.

Presence messages look like:

```json
{
  "documentId": 1,
  "activeUsers": [
    {
      "userId": 2,
      "name": "Teammate",
      "email": "teammate@example.com",
      "status": "TYPING",
      "joinedAt": "2026-04-29T10:00:00",
      "lastSeenAt": "2026-04-29T10:01:00"
    }
  ],
  "updatedAt": "2026-04-29T10:01:00"
}
```

Supported presence statuses are `VIEWING` and `TYPING`.

## Environment

The app defaults to local backend URLs. Copy `.env.example` to `.env.local` in
this folder when you need to override them:

```env
REACT_APP_API_BASE_URL=http://localhost:8080
REACT_APP_WS_URL=http://localhost:8080/ws
```

Use `.env.production.example` as a reference for Render's Static Site environment.
Do not add `/api` or a trailing slash to the API origin. SockJS uses the HTTP(S)
`/ws` URL. Restart `npm start` after local env changes; rebuild/redeploy after
production env changes. `REACT_APP_*` variables are public: database credentials
and JWT signing secrets belong only on the backend.

Install dependencies with `npm ci` using the committed lockfile. Full Render and
Neon setup, smoke checks, and troubleshooting are in [DEPLOYMENT.md](../DEPLOYMENT.md).

## Available Scripts

### `npm start`

Runs the app in development mode. Open [http://localhost:3000](http://localhost:3000).

### `npm test -- --watchAll=false --runInBand`

Runs the test suite once in a single process.

### `npm run build`

Builds the production bundle into the `build` folder.
