---
title: "Architecture of the Components"
linkTitle: "Components"
description: "What all components of SAK share: the shared parts, the rules of list-based apps and the frontend."
weight: 20
---

# Architecture of the Components

What all components of SAK share: the apps of the backend and the parts next to them, the health and the backups. It
adds to [SYSTEM.md](../SYSTEM.md) and never repeats it; each component adds to this document in its own one.

## The components

| Component      | Document                     | Backend                                  | Frontend              | Tables                                                                   |
|----------------|------------------------------|------------------------------------------|-----------------------|--------------------------------------------------------------------------|
| Static hosting | [HOSTING.md](HOSTING.md)     | `io.pelle.sak.backend.hosting`, `sak-s3` | `projects/hosting/`   | `s3_buckets`, `s3_buckets_domains`                                       |
| Redirects      | [REDIRECTS.md](REDIRECTS.md) | `io.pelle.sak.backend.redirects`         | `projects/redirects/` | `redirects`                                                              |
| Notes          | [NOTES.md](NOTES.md)         | `io.pelle.sak.backend.notes`             | `projects/notes/`     | `note_lists`, `notes`, `notes_labels`, `notes_files`                     |
| Grocery        | [GROCERY.md](GROCERY.md)     | `io.pelle.sak.backend.grocery`           | `projects/grocery/`   | `grocery_lists`, `grocery_categories`, `grocery_items`                   |
| Todo           | [TODO.md](TODO.md)           | `io.pelle.sak.backend.todo`              | `projects/todo/`      | `todo_lists`, `todo_items`                                               |
| Health         | [HEALTH.md](HEALTH.md)       | `io.pelle.sak.backend.health`, `sak-dns` | `projects/health/`    | none                                                                     |
| Backup         | [BACKUP.md](BACKUP.md)       | `io.pelle.sak.backend.restic`            | `projects/backups/`   | `restic_repositories`, `restic_backup_plans`, `restic_backup_plan_paths` |

The first five are the apps. The login, the settings, the labels and the file storage are no apps; all apps share
them (refer to [Shared parts](#shared-parts)). The health is no app either: it is a maintenance page (`FRONTEND-1`).
The backups have a part in the backend, the repositories of restic, and a desktop app, sakbackup, which runs restic.

## Shared parts

- `SHARED-1` **Login and roles.** The login gives a session; every session has a role. Only `GET /api/v1/info` and
  the login answer without a session.
- `SHARED-2` **Labels.** The labels of a user are shared by all apps and seen by nobody else. An app that links labels
  drops its links when a label is deleted.
- `SHARED-3` **The file storage.** Every app keeps its files in one storage: each upload is a file with an id and its
  owner, the type is told by the first bytes and never taken from the uploader.

## List-based apps

An app whose items belong to lists of the user keeps to these rules; the notes, the grocery lists and the todo lists
are such apps.

- `LIST-1` **A first list.** When a user signs in who never had a list of their own, each list-based app makes them a
  private list named `My <entity>` in their language, e.g. "My Todos"; the shared lists of others do not count. The
  owner of a list renames, shares and deletes it, the last one too; a deleted list does not come back. A new item
  always names its list; no request makes a list on the side.
- `LIST-2` **Shared lists.** A list has a shared flag; a shared list is shared with all other users, who can add items
  to it and delete items from it, and change its icon and its categories. Only its owner renames, shares or unshares it
  and deletes it. The owner of an item is who added it (`MODEL-5`), but the access follows the list (`MODEL-6`): every
  user can delete an item of a shared list, also one another user created.
- `LIST-3` **The lists first.** A list-based app, in the web app and on Android, opens on the overview of all lists of
  the user: their own lists and the shared lists of the others. From there the user opens a list; a list has a back
  action that leads to the overview. An Android app remembers the list that was open when it was left and opens it
  again; when the user went back to the overview, or the list is gone, it opens on the overview. A sign-out forgets
  the list.
- `LIST-4` **Changes reach every client.** A change of a list or of its items, its deletion included, reaches every
  open client of each user who sees the list, in the web app and on Android alike: the backend sends a server-sent
  event with the id of the list and nothing of its data, and the client loads the list again. A client listens only
  while it is in the foreground; when it comes back, it loads again what it shows, so it sees what changed meanwhile.

## Web app

- `FRONTEND-1` **One page for each app**, listed on the start page; the maintenance pages (health, settings) are
  listed below them. The look is in [UI-UX.md](../../UI-UX.md), the rules of the code in
  [AGENT.md](../../../../AGENT.md).
- `FRONTEND-2` **The tabs of a detail view are child routes**, for example the tabs of a bucket or the scopes of the
  settings.
