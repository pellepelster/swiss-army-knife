---
title: "Review: data model"
linkTitle: "Data model"
description: "The review of the data model of the backend (the changesets, the repositories of sak-model, the MODEL rules): what is still open."
weight: 11
---

# Review: data model

**Created:** 2026-11-22

Scope: the schema of the backend as changesets `0001-initial` to `0006-drop-layout-migration-columns` make it (19
tables), the repositories of `sak-model` and the services that read through them, against `MODEL-1` to `MODEL-8` of
[MODEL.md](../architecture/MODEL.md). The question: bugs, gaps between the rules and the schema, and what the
schema could check itself.

**Checked again:** 2026-11-24, with the migrations up to `V12__foreign_key_indexes`: `DM-2` to `DM-5` are fixed, `DM-6`
is open. A repository of restic keeps its data on the volume or in the S3 storage, whichever the settings name.

What is good: every delete is a soft delete, and every name that must be unique has a partial unique index on the live
rows (`MODEL-3`); every table has `owner`, `created_at`, `updated_at` and `deleted_at`, which `SchemaTest` enforces
(`MODEL-4`, `MODEL-5`); the access checks are in the sql of the repositories, a backup plan checks in its own
transaction that its repository is the owner's and is live (`MODEL-6`); the database itself checks the amounts and units
of the grocery items, the due dates and priorities of the todo items and the targets of the redirects; the three tables
with data on the data volume (`files`, `restic_repositories`, `s3_buckets`) have a resource id with a unique index over
the deleted rows too (`MODEL-8`).

Only the open findings are listed.

## Could fix

### `DM-6` An upload is never removed

A file that is uploaded and never shown by a note or an item stays for ever: there is no delete and no sweep. Since
the files have their resource ids, every upload is a blob of its own, so the storage only grows. `NOTES-2` covers the
files a note no longer shows, not the ones never shown. That a user has no quota is `SEC-29` of
[the review of the security](security-review.md).

## Notes

- `DM-1` is withdrawn: the notes are visible to their owner only, the lists of the notes are prepared but not exposed
  (`NOTES-1`), every read and change of a note checks `notes.owner`. So the files and the labels of a note, looked up
  under the user who reads it, are always its owner's. Lists of notes shared with others (`LIST-2`) would read the
  notes through the visibility of their list, serve the files of a note through the note, as the grocery lists serve
  the photo of an item, and need a decision on the labels of another user's note, which belong to that user.
- `files.resource_id` and `s3_buckets.resource_id` are `NOT NULL DEFAULT ''`: sqlite adds a column that is not null
  only with a default. A row inserted without a resource id gets `''`, a second one fails on the unique index;
  `FileRepository` and `S3BucketRepository` set it on every insert.
- The tokens of the repositories of restic, the secret keys of the buckets and the settings are plain in the
  database, as [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts ("the database is not encrypted at rest"); the token of
  a repository is compared in constant time.
