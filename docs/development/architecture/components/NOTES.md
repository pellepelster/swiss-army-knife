---
title: "Notes"
description: "The app of post-its in lists, with text, labels, photos and recordings, and the text discovered in them."
weight: 30
---

# Notes

Adds to [the architecture of the components](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Post-its from text, photos or recordings, in lists. A user can let the AI discover the text in a photo or a recording
and add it to the note.

## General

- `NOTES-1` **The notes are a list-based app.** The notes are a list-based app (refer to [List apps](LIST-APPS.md)). The lists are prepared but not exposed to the user: their first list (`LIST-1`), "My
  Notes", holds all their notes.

## Data

- `NOTES-2` **A file stays in the storage.** It stays when a note no longer shows it; other notes may show it.
- `NOTES-3` **Discovered text is kept with its file.** The text discovered in a file is kept with the file, so a file is
  sent to the AI once.

## API

- `NOTES-4` **The api manages the notes and labels.** Create, change, read and delete a note; read the notes page by
  page, of a label or all; read the labels the notes have (`GET /api/v1/notes/labels`), the only ones the apps filter
  by.
- `NOTES-5` **The backend discovers the text of a file.** Discover the text in a photo or a recording of the user (`POST
  /api/v1/files/{id}/discover/text`). Every user may ask whether it can be done now (`GET /api/v1/notes/settings`,
  `discoverText`): the AI turned on by the user for the notes and a provider set up (`aiConfigured`); the backend
  checks both on every discovery.

## Android

- `NOTES-6` **The Android app.** The notes have an Android app of their own (`android/notes`), on top of
  `android/core`, with a sign-in of its own. It opens on the wall of the notes and has what the web app has: the notes
  in their colors, a filter by one label, a note created from text, a photo or a recording, its text, color, labels,
  photos and recordings changed, a note deleted, the text discovered in a photo or a recording (`NOTES-5`, on the same
  terms as in the web app). The notes have no change stream: the wall loads again when it is pulled down
  (`ANDROID-3`) and when the app comes back to the front.
- `NOTES-7` **Photos shared with the Android app.** Photos another app shares with the notes app (one or several)
  become one new note, with the text shared along with them as its text; a note takes at most 20 photos, the others
  are left out and the app says so. Photos shared before the notes are loaded wait for them; without a session they
  are not kept, the sign-in asks to share them again after signing in.

## Settings

- `ai` (scope `notes`, a setting of each user): the AI features of the notes (`NOTES-5`), off by default; it counts
  only while the AI of the instance is set up (scope `ai`).

## External systems

- **OpenRouter**, only with the AI features turned on and only when the user starts a discovery (`NOTES-5`): the
  photo or the recording goes to the model of the deployment, through providers that do not collect the data.
  OpenRouter sees the file and the API key of the deployment, not whose it is.
