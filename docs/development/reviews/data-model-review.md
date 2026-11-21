---
title: "Review: data model"
linkTitle: "Data model"
description: "The review of the data model of the backend (the changesets, the repositories of sak-model, the MODEL rules): what is still open."
weight: 11
---

# Review: data model

**Created:** 2026-11-22

Scope: the schema of the backend as changesets `0001-initial` to `0004-files-resource-id` make it (18 tables), the
repositories of `sak-model` and the services that read through them, against `MODEL-1` to `MODEL-8` of
[SYSTEM.md](../architecture/SYSTEM.md#model). The question: bugs, gaps between the rules and the schema, and what the
schema could check itself.

What is good: every delete is a soft delete, and every name that must be unique has a partial unique index on the live
rows (`MODEL-3`); every table has `owner`, `created_at`, `updated_at` and `deleted_at`, which `SchemaTest` enforces
(`MODEL-4`, `MODEL-5`); the access checks are in the sql of the repositories, a backup plan checks in its own
transaction that its repository is the owner's and is live (`MODEL-6`); the database itself checks the amounts and
units of the grocery items, the due dates and priorities of the todo items and the targets of the redirects; both
tables with data on the data volume (`files`, `restic_repositories`) have a resource id with a unique index over the
deleted rows too (`MODEL-8`).

Only the open findings are listed.

## Should fix

### `DM-1` The notes of another user in a shared list lose their photos, recordings and labels

`NoteService.notes()` looks up the labels and the files of every note under the user who reads it
(`labels.byIds(owner, …)`, `files.byIds(owner, …)`), and the routes of the files serve a file only to the user who
uploaded it (`servedFiles` in `SakFiles.kt`). Who reads a note of another user in a shared list sees it without its
photos, recordings and labels; who changes it gets `UNKNOWN_PHOTO`, as `check()` looks up the files under the user who
changes it too. The grocery lists do it right: the photo of an item is joined through the visible item
(`GroceryRepository`). It does not happen yet, the lists of the notes are not exposed (`NOTES-1`); it does as soon as
they are.

Fix: read the files and the labels of a note through the visibility of the note, as the grocery lists do, and serve
a file to every user who sees a note or an item that shows it.

### `DM-2` A repository of restic and a backup plan can not be deleted

Neither `ResticRepositoryRepository` nor `ResticBackupPlanRepository` has a delete, though `BACKUP-7` says "a deleted
repository does not come back" and `insertFirst` counts the deleted rows. A repository that goes has to take its
backup plans with it: `restic_backup_plans.repository_id` refers to it, nothing else handles them.

Fix: decide whether they are deleted; if so, a delete of each, the one of a repository deleting its backup plans in
the same transaction.

## Could fix

### `DM-3` The timestamps are declared in two ways

The tables of `createTable` have their timestamps as `TEXT`; the tables written in plain sql (`grocery_items`,
`grocery_categories`, `todo_items`, `redirects`) as `TIMESTAMP`, which sqlite gives the numeric affinity. It works, the
values are text that is no number, but one way reads better; the primary keys are written in two ways as well.

### `DM-4` Foreign keys without an index

`notes.list_id`, `notes_files.file_id`, `grocery_items.category_id`, `grocery_items.photo_file_id` and
`restic_backup_plans.repository_id` have no index; `deleteCategory` changes the items `WHERE category_id = ?` by
reading the whole table. The tables are small, an index each is cheap.

### `DM-5` Positions that are not unique

`s3_buckets_domains` has a unique index on `(bucket_name, position)` among the live rows; `notes_files (note_id,
position)` and `restic_backup_plan_paths (plan_id, position)` have none. The repositories write the positions in one
transaction, an index would only make the rule explicit.

### `DM-6` An upload is never removed

A file that is uploaded and never shown by a note or an item stays for ever: there is no delete and no sweep. Since
the files have their resource ids, every upload is a blob of its own, so the storage only grows. `NOTES-2` covers the
files a note no longer shows, not the ones never shown.

### `DM-7` The column `files.sha256` is left for the migration only

It names where the content of a file was before the resource ids; only `FileLayoutMigration` reads it. It goes with
the migration in a later release.

## Notes

- `files.resource_id` is `NOT NULL DEFAULT ''`: sqlite adds a column that is not null only with a default. A row
  inserted without a resource id gets `''`, a second one fails on the unique index; `FileRepository` sets it on every
  insert.
- The tokens of the repositories of restic, the secret keys of the buckets and the settings are plain in the
  database, as [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts ("the database is not encrypted at rest"); the token of
  a repository is compared in constant time.
