---
title: "Review: API"
linkTitle: "API"
description: "The review of the routes below /api/v1 against the API contract (API-1 to API-5)."
weight: 2
---

# Review: API

**Created:** 2026-10-31

Scope: all routes below `/api/v1`: the login (`sak-auth`), the buckets and their access logs (`sak-logs`), the
redirects, the labels, the notes, the grocery lists, the files, the settings, the configuration, the health and the
info. The contract is in [apps/INDEX.md](../architecture/apps/INDEX.md#api).

What is good: every route has its OpenAPI description, the errors of a request are one readable sentence each, the
lists are paged with the same parameters, the times use `apiTime()`, and no domain class crosses the API (`API-4`).

## Must fix

### ~~`AR-1` Every admin session can read the password of the mail server~~

**Fixed:** `/config` is below `authenticate(API_SIGNATURE_AUTH)` only and answers with `Cache-Control: no-store`; a
session, also one of an admin, gets 401. `requireRole` answers 401 to a request without a principal, so no provider
that refuses nothing on its own leaves a route open.

`GET /api/v1/config` answers the password of the SMTP server in clear text. Only sakctl uses the route (a signed
request, `sak-cli/src-tauri/src/core/config.rs`), but the route is below `authenticate(SESSION_AUTH,
API_SIGNATURE_AUTH)`, so every browser session with the role `admin` gets the password too. The answer also has no
`Cache-Control: no-store`, unlike `GET /static-hosting/{bucket}/secret` and the login.

Fix: put `/config` below `authenticate(API_SIGNATURE_AUTH)` only, and answer with `Cache-Control: no-store`.

### `AR-2` The limit of the text discovery counts requests that cost nothing

`POST /api/v1/files/{id}/discover/text` takes from the limit (30 an hour for each user) before anything else. A
request for an unknown file, for a file that is no image or recording, while the AI features are off, without an API
key, or for a file whose text is already known (`FileTextService.discover` answers it from the database) uses up the
limit, but no request goes to OpenRouter. A page that shows the known text of a note again and again blocks the
discovery of new texts.

Fix: take from the limit only just before the call to OpenRouter, e.g. `FileTextService.discover` gets the limiter,
or answers a result that tells the route to take from it first.

## Contract

### `AR-3` Errors without the error body

`API-1` says that errors answer `{"errors": [...]}`. Many answers have no body:

- 404 of every route (`call.respond(HttpStatusCode.NotFound)`, also `idParameter()` for an id that is no number);
- 401 of `/auth/login/exchange` and `/auth/login/password`;
- 429 of `/auth/login/link` and `/auth/login/password` (the 429 of the text discovery has a body);
- 503 of `/static-hosting/{bucket}/access-logs/metrics` and `/search`.

A client can not show why its request failed; `apiErrorMessage` in the frontend falls back to texts of its own.

Fix: one helper for each status with its message (e.g. `respondNotFound("There is no such note.")`), and the
429 and 503 with a message. Or, if a bare 404 and 401 are enough, write that exception into `API-1`.

### `AR-4` The health is a list without pages

`GET /api/v1/health` answers a bare JSON array (`List<HealthStatusResponse>`). `API-2` says that lists are paged
(`{"items", "pagination"}`); the categories, the lists of grocery and the scopes of the settings, which are also
small and complete, are paged with `page.of(all)`.

Fix: answer `page.of(page.of(statuses))` with the page parameters, or write the exception into `API-2`.

### `AR-5` A missing entity that a request refers to has four different statuses

| Request | Refers to | Status when it is missing |
|---|---|---|
| `POST /redirects`, `PUT /redirects/{source}` | a bucket | 422 |
| `POST /grocery/items` | a list | 404 |
| `POST /notes`, `PUT /notes/{id}` | a photo or a recording | 400 |
| `POST /notes` | the list of the user | 409 |

A client can not know which status to expect. The contract has no rule for it.

Fix: a rule `API-6`, e.g. "404 only for the resource of the path; a missing entity that the body refers to is a
422 with its message", and the routes changed to it.

## To think about

### `AR-6` Grocery has no read of one list or one item

Every other resource has `GET /{id}` (buckets, redirects, labels, notes). Grocery has `GET /lists` and
`GET /lists/{id}/items`, but no `GET /lists/{id}` and no `GET /items/{id}`. The page reads all lists to show one.

### `AR-7` The suggestions ignore a bad list and page at most ten items

`GET /api/v1/grocery/suggestions?listId=abc` ignores the list without an error, unlike `offset` and `limit`,
which answer 400. The service gives at most `MAX_SUGGESTIONS` (10) items, which the route then pages; `limit=50`
gives 10, and an `offset` of 10 or more gives none. Either a 400 for a bad `listId` and no pages (a fixed short list
with `limit` as its size), or the pages over all suggestions.

## Small items

- `AR-8` `BucketResponse.subdomain` is missing "for a name with dots, which were allowed before";
  `S3BucketService.subdomain` checks the name only for these old names. No bucket of today has a dot. Remove the
  check and the comment; `subdomain` is then missing only without a base domain.
- `AR-9` The OpenAPI text of `POST /notes` and `PUT /notes/{id}` names the photos but not the recordings, in the
  description and in the 400.
