---
title: "Architecture of the Apps"
linkTitle: "Apps"
description: "What all apps of SAK share: the backend layers, the persistence, the settings, the api, the shared parts, the frontend and privacy."
weight: 20
---

# Architecture of the Apps

What all apps of the backend share. It adds to [SYSTEM.md](../SYSTEM.md) and never repeats it; each app adds to this
document in its own one.

## The apps

| App | Document | Backend | Frontend | Tables | Settings |
|---|---|---|---|---|---|
| Static hosting | [HOSTING.md](HOSTING.md) | `io.pelle.sak.backend.hosting`, `sak-s3` | `hosting/` | `s3_buckets`, `s3_buckets_domains` | none |
| Redirects | [REDIRECTS.md](REDIRECTS.md) | `io.pelle.sak.backend.redirects` | `redirects/` | `redirects` | none |
| Notes | [NOTES.md](NOTES.md) | `io.pelle.sak.backend.notes` | `notes/` | `notes`, `notes_labels`, `notes_files` | `notes` |
| Health | [HEALTH.md](HEALTH.md) | `io.pelle.sak.backend.health` | `health/` | none | none |

The login, the settings, the labels and the file storage are no apps; all apps share them (refer to
[Shared parts](#shared-parts)).

## Backend layers

- **Routes → services → repositories.** All business logic is in a service of its entity, next to the routes of
  its app. A service uses only other services and its own repository. A repository is used only by its service;
  routes use services, never repositories.
- **The database is only in `sak-model`.** Only the repository of an entity touches the database.

## Persistence

- **Soft delete only.** No row is ever deleted for real. A delete marks the row as deleted; every read skips such rows.
  A value that must be unique is unique among the live rows only. A deleted bucket keeps its name taken until it is
  purged (refer to [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md), the only planned purge).
- **Every row records its time.** When it was created, last updated and deleted, set by the repository from one
  clock.
- **Secrets stay with what they belong to.** The secret key of a bucket is stored with the bucket, but is never part
  of the bucket that reaches the api. Secrets that belong to no entity, such as the signing key of the login, are
  settings.

## Settings

- **Storage.** A setting is one value for a name and a scope; a scope is a set of settings that belong together,
  for example `system`. Their one service
  hands the values out as they are; whoever uses a setting that is a secret keeps it from showing. A secret never
  shows in a log line. The values are plain; the whole
  database is to be encrypted ([FDR-002](../../fdr/FDR-002-sqlite-encryption.md)).
- **Settings an admin changes.** Each setting that an admin changes at run time declares its scope, its type and its
  default, and the code reads and writes it with that type. One api and one page show and change the settings of all
  scopes, one scope at a time: the api reads and sets the values of a scope, the page shows each scope as a tab. A
  change of a scope is validated completely before any value is set. A read-only setting shows a value the backend
  gives, for example a model from its configuration; it is never stored and can not be changed.
  A setting can require another one that is on, also in another scope; while that one is off, it is off to the code
  and the apps and can not be changed, and the settings page shows its stored value greyed out.

## API

- **Errors** answer `{"errors": [{"message": "..."}]}`, one readable sentence for each problem
  (`ApiErrorResponse` with its `ApiErrorMessage`s). Inside the backend, an error that can reach a client is always an
  `ErrorMessage` (`sak-api`), its message one string; it becomes an `ApiErrorMessage` only at the api.
- **Lists** are paginated: `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked for with `offset`
  and `limit`; `limit` is 50 by default and at most 100.
- **Times** are ISO 8601 in UTC with milliseconds, always the same form: `2026-10-29T08:00:00.000Z`. That holds for
  every time the api answers (when something was created, updated or computed, when a session ends, the times of
  the logs and the metrics). Something that never ends has no time rather than one far away.
- **Request and response classes** are the boundary: a domain class never crosses the api, it is mapped to its
  response class.
- **Authentication:** a session token, or a request sakctl signs with the api key of the deployment (its public
  half in the configuration). A signed request holds for the host of the backend, its method and path, a time no more
  than two minutes away and a nonce taken once; it carries no body and no secret, so a request that went to another
  host or is replayed gets nothing. A
  session comes from the password login of the admin (always there) or from the e-mail login (only in the multi
  user mode, with a mail server); refer to [FDR-005](../../fdr/FDR-005-login.md). Without a session or a signature, only the login and
  `GET /api/v1/info` answer.

## Shared parts

- **Login and roles.** The login (refer to [FDR-005](../../fdr/FDR-005-login.md)) gives a session; every app needs one
  and the role `admin`. Only `GET /api/v1/info` and the login answer without a session.
- **Labels.** The labels of a user are shared by all apps and seen by nobody else. An app that links labels drops its
  links when a label is deleted.
- **The file storage.** Every app keeps its files in one storage: each upload is a file with an id and its owner,
  the same content is stored once, the type is told by the first bytes and never taken from the uploader. An image
  with location metadata is refused. Thumbnails are made when they are first asked for.
- **Domains.** A domain is served by one thing only: the apps themselves (`sak.<base domain>`,
  `api.<base domain>`), a bucket or a redirect. `DomainRegistry` keeps buckets and redirects from taking a domain of
  the other. The backend configures caddy with all of them and again after every change; the health of the domains
  is checked by asking name servers directly.

## Frontend

- **One page for each app**, listed on the start page; the maintenance pages (health, settings) are listed below
  them. The look is in [UI-UX.md](../../UI-UX.md), the rules of the code in [AGENT.md](../../../../AGENT.md).
- **The tabs of a detail view are child routes**, for example the tabs of a bucket or the scopes of the settings.
- **Delete and undo.** A note, a photo or a recording of a note goes at once; a toast offers to bring it back for 5
  seconds, the backend deletes it afterwards.

## Privacy in the apps

- **Activation and consent first** (refer to [SYSTEM.md](../SYSTEM.md#privacy)). An app that sends data to a third
  party needs a setting that turns it on, off by default, and, where the data comes from the user, the user's
  consent before each transfer.
- The connections of each app and what they reveal are in its own document.
