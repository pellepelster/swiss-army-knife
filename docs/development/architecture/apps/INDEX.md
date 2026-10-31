---
title: "Architecture of the Apps"
linkTitle: "Apps"
description: "What all apps of SAK share: the backend layers, the settings, the api, the shared parts, the rules of list-based apps, the frontend and privacy."
weight: 20
---

# Architecture of the Apps

What all apps of the backend share. It adds to [SYSTEM.md](../SYSTEM.md) and never repeats it; each app adds to this
document in its own one.

## The apps

| App            | Document                     | Backend                                  | Frontend     | Tables                                               | Settings |
|----------------|------------------------------|------------------------------------------|--------------|------------------------------------------------------|----------|
| Static hosting | [HOSTING.md](HOSTING.md)     | `io.pelle.sak.backend.hosting`, `sak-s3` | `hosting/`   | `s3_buckets`, `s3_buckets_domains`                   | none     |
| Redirects      | [REDIRECTS.md](REDIRECTS.md) | `io.pelle.sak.backend.redirects`         | `redirects/` | `redirects`                                          | none     |
| Notes          | [NOTES.md](NOTES.md)         | `io.pelle.sak.backend.notes`             | `notes/`     | `note_lists`, `notes`, `notes_labels`, `notes_files` | `notes`  |
| Grocery        | [GROCERY.md](GROCERY.md)     | `io.pelle.sak.backend.grocery`           | `grocery/`   | `grocery_lists`, `grocery_items`, `grocery_products` | none     |
| Health         | [HEALTH.md](HEALTH.md)       | `io.pelle.sak.backend.health`            | `health/`    | none                                                 | none     |

The login, the settings, the labels and the file storage are no apps; all apps share them (refer to
[Shared parts](#shared-parts)).

## Backend layers

- `LAYER-1` **Routes → services → repositories.** All business logic is in a service of its entity, next to the routes
  of its app. A service uses only other services and its own repository. A repository is used only by its service;
  routes use services, never repositories. How the data is stored is in [Model](../SYSTEM.md#model).

## Settings

- `SETTINGS-1` **Settings an admin changes.** Each setting that an admin changes at run time declares its scope, its
  type and its default, and the code reads and writes it with that type. One api and one page show and change the
  settings of all scopes, one scope at a time: the api reads and sets the values of a scope, the page shows each scope
  as a tab. A change of a scope is validated completely before any value is set. A read-only setting shows a value the
  backend gives, for example a model from its configuration; it is never stored and can not be changed. A setting can
  require another one that is on, also in another scope; while that one is off, it is off to the code and the apps and
  can not be changed, and the settings page shows its stored value greyed out.

## API

- `API-1` **Errors** answer `{"errors": [{"message": "..."}]}`, one readable sentence for each problem
  (`ApiErrorResponse` with its `ApiErrorMessage`s). Inside the backend, an error that can reach a client is always an
  `ErrorMessage` (`sak-api`), its message one string; it becomes an `ApiErrorMessage` only at the api.
- `API-2` **Lists** are paginated: `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked for with
  `offset` and `limit`; `limit` is 50 by default and at most 100.
- `API-3` **Times** are ISO 8601 in UTC with milliseconds, always the same form: `2026-10-29T08:00:00.000Z`. That
  holds for every time the api answers (when something was created, updated or computed, when a session ends, the
  times of the logs and the metrics). Something that never ends has no time rather than one far away.
- `API-4` **Request and response classes** are the boundary: a domain class never crosses the api, it is mapped to its
  response class.
- `API-5` **Authentication:** a session token, or a request sakctl signs with the api key of the deployment (its
  public half in the configuration). A signed request holds for the host of the backend, its method and path, a time
  no more than two minutes away and a nonce taken once; it carries no body and no secret, so a request that went to
  another host or is replayed gets nothing. A session comes from the password login of the admin (always there) or
  from the e-mail login (only in the multi user mode, with a mail server); refer to
  [FDR-005](../../fdr/FDR-005-login.md). Without a session or a signature, only the login and `GET /api/v1/info`
  answer.

## Shared parts

- `SHARED-1` **Login and roles.** The login (refer to [FDR-005](../../fdr/FDR-005-login.md)) gives a session; every
  app needs one and the role `admin`. Only `GET /api/v1/info` and the login answer without a session.
- `SHARED-2` **Labels.** The labels of a user are shared by all apps and seen by nobody else. An app that links labels
  drops its links when a label is deleted.
- `SHARED-3` **The file storage.** Every app keeps its files in one storage: each upload is a file with an id and its
  owner, the same content is stored once, the type is told by the first bytes and never taken from the uploader. An
  image with location metadata is refused. Thumbnails are made when they are first asked for.
- `SHARED-4` **Domains.** A domain is served by one thing only: the apps themselves (`sak.<base domain>`, `api.<base
  domain>`), a bucket or a redirect. `DomainRegistry` keeps buckets and redirects from taking a domain of the other.
  The backend configures caddy with all of them and again after every change; the health of the domains is checked by
  asking name servers directly.
- `SHARED-5` **Prepared at the sign-in.** An app that prepares the data of a user, e.g. their first list, does it as a
  `LoginListener` (`sak-auth`), listed in `SakServices.loginListeners`. Each sign-in that issues a session calls it
  before the session is answered, so the pages find the data; a signed request of sakctl is no sign-in. It must be
  idempotent. When it fails, the sign-in still succeeds, and the log tells neither the user nor the message.

## List-based apps

An app whose items belong to lists of the user keeps to these rules; the notes and the grocery lists are such apps.

- `LIST-1` **A list from the sign-in.** When a user signs in, the app makes the private list "Default" for them if
  they have no list of their own (`SHARED-5`). It is a list like the others: its owner renames, shares and deletes it.
  A new item always names its list; no request makes a list on the side.
- `LIST-2` **Shared lists.** A list has a shared flag; a shared list is shared with all other users, who can add items
  to it and delete items from it. Unlike `MODEL-5`, where a row belongs to its owner, every user can delete an item of
  a shared list, also one another user created.

## Frontend

- `FRONTEND-1` **One page for each app**, listed on the start page; the maintenance pages (health, settings) are
  listed below them. The look is in [UI-UX.md](../../UI-UX.md), the rules of the code in
  [AGENT.md](../../../../AGENT.md).
- `FRONTEND-2` **The tabs of a detail view are child routes**, for example the tabs of a bucket or the scopes of the
  settings.
- `FRONTEND-3` **Delete and undo.** A note, a photo or a recording of a note, and a grocery item checked, take effect
  at once; a toast centered at the bottom offers to undo it for 5 seconds, 1.5 for the grocery items, the backend
  learns it afterwards.

## Privacy in the apps

- `APP-PRIVACY-1` **Activation and consent first** (refer to [SYSTEM.md](../SYSTEM.md#privacy)). An app that sends
  data to a third party needs a setting that turns it on, off by default, and, where the data comes from the user, the
  user's consent before each transfer.
- `APP-PRIVACY-2` The connections of each app and what they reveal are in its own document.
