---
title: "Architecture of the Apps"
linkTitle: "Apps"
description: "What all apps of SAK share: the shared parts, the rules of list-based apps, the frontend and privacy."
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

## Shared parts

- `SHARED-1` **Login and roles.** The login gives a session; every
  app needs one and the role `admin`. Only `GET /api/v1/info` and the login answer without a session.
- `SHARED-2` **Labels.** The labels of a user are shared by all apps and seen by nobody else. An app that links labels
  drops its links when a label is deleted.
- `SHARED-3` **The file storage.** Every app keeps its files in one storage: each upload is a file with an id and its
  owner, the same content is stored once, the type is told by the first bytes and never taken from the uploader.
  Thumbnails are made when they are first asked for.

## List-based apps

An app whose items belong to lists of the user keeps to these rules; the notes and the grocery lists are such apps.

- `LIST-1` **A list from the sign-in.** When a user signs in, the app makes the private list "Default" for them if
  they have no list of their own (`SHARED-5`). It is a list like the others: its owner renames, shares and deletes it.
  A user keeps at least one list of their own, the last one can not be deleted; the shared lists of the others do
  not count, their owners can take them away. A new item always names its list; no request makes a list on the
  side.
- `LIST-2` **Shared lists.** A list has a shared flag; a shared list is shared with all other users, who can add items
  to it and delete items from it. Unlike `MODEL-5`, where a row belongs to its owner, every user can delete an item of
  a shared list, also one another user created.
- `LIST-3` **The lists first.** A list-based app, in the web ui and on Android, opens on the overview of all lists of
  the user: their own lists and the shared lists of the others. From there the user opens a list; a list has a back
  action that leads to the overview. An Android app remembers the list that was open when it was left and opens it
  again; when the user went back to the overview, or the list is gone, it opens on the overview. A sign-out forgets
  the list.

## Web Frontend

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
