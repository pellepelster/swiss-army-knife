---
title: "Notes"
description: "The app of post-its with text, labels, photos and recordings, and the text discovered in them."
weight: 30
---

# Notes

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Post-its from text, photos or recordings. A user can let the AI discover the text in a photo or a recording and add
it to the note.

## Parts

| Part | What it is |
|---|---|
| `io.pelle.sak.backend.notes` | The notes (`NoteService`), their settings (`NotesSettingsService`), the routes of the app |
| `io.pelle.sak.backend.files` | The text discovery (`FileTextService`) in the files of the file storage |
| `io.pelle.sak.backend.openrouter` | `OpenRouterManager`: the scope **AI**, the one place the clients of OpenRouter are made |
| `sak-openrouter` | The clients of OpenRouter: the vision model, the speech model, the check of an API key |
| `sak-frontend/src/app/notes/` | The page of the notes, its store, the recorder |
| `notes`, `notes_labels`, `notes_files` | A note, its labels and its photos and recordings |

## Data

- **A note** belongs to its owner, the user that created it, and is seen only by them. It has a text, a color,
  labels (the shared ones), and photos and recordings (files of the file storage, in their order).
- **A file stays** in the storage when a note no longer shows it; other notes may show it (refer to
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md) for the cleanup).
- **The text discovered** in a file is kept with the file; a file is sent to the AI once.

## API

- Create, change, read and delete a note; read the notes page by page, of a label or all.
- Discover the text in a photo or a recording of the user (`POST /api/v1/files/{id}/discover/text`).

## Settings

| Scope | Setting | What it does |
|---|---|---|
| `notes` | **AI features** | Lets the notes discover text; requires **AI features** of the scope `ai` |
| `ai` | **AI features** | Lets the apps send data to an AI provider at all; off by default |
| `ai` | **API key** | The key of the OpenRouter account, shown with its first 4 characters |
| `ai` | **Vision model**, **Speech model** | The models of the configuration of the backend, read-only |

The page of the notes reads only its own scope, as the apps use it (`?effective=true`).

## External systems

- **OpenRouter** gets a photo or a recording when a user asks for its text, with
  `provider.data_collection: deny`, so providers that collect data are not asked. OpenRouter sees the address of
  the server, not the one of the user.
- **A new API key** is checked at OpenRouter, only while **AI features** of the scope `ai` is on.
- **Activation:** both switches **AI features** must be on, and the user asks for each file
  (refer to [the architecture of the apps](INDEX.md#privacy-in-the-apps)).

## Decisions

- **A file is read once.** The text discovered is kept with the file; asking again answers the kept text, also with
  the AI turned off since.
- **The models are configuration**, the API key is a setting: an admin changes the key without a new deploy.
- **A limit on discovery.** Each user can discover a limited number of texts an hour, because it costs money at
  OpenRouter.

## Related

- **FDRs:** [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md) (the files nothing refers to)
- **Accepted risks:** the database with the API key is not encrypted yet (refer to
  [ACCEPTED-RISKS.md](../../ACCEPTED-RISKS.md))
