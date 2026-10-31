---
title: "Notes"
description: "The app of post-its in lists, with text, labels, photos and recordings, and the text discovered in them."
weight: 30
---

# Notes

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Post-its from text, photos or recordings, in lists. A user can let the AI discover the text in a photo or a recording
and add it to the note.

## General

- `NOTES-1` **Lists.** The notes are a list-based app (refer to [List-based apps](INDEX.md#list-based-apps)): every
  note belongs to one list of its owner. The lists and their shared flag are prepared but not exposed to the user: all
  notes of a user are on their list from the sign-in (`LIST-1`). A user who has not signed in since they have no
  list, e.g. sakctl acting as the admin, can not create a note.

## Data

- `NOTES-2` **A file stays** in the storage when a note no longer shows it; other notes may show it (refer to
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md) for the cleanup).
- `NOTES-3` **The text discovered** in a file is kept with the file; a file is sent to the AI once.

## API

- `NOTES-4` Create, change, read and delete a note; read the notes page by page, of a label or all.
- `NOTES-5` Discover the text in a photo or a recording of the user (`POST /api/v1/files/{id}/discover/text`).

