---
title: "Backup"
description: "sak-backup, the desktop app for the backups of a SAK instance, and what it shares with sakadm."
weight: 50
---

# Backup

A desktop app, no app of the backend (refer to [the architecture of the apps](INDEX.md#the-apps)). Adds to
[SYSTEM.md](../SYSTEM.md). The backups it is for are a proposal, in
[sak-backup/README.md](https://github.com/pellepelster/swiss-army-knife/blob/main/sak-backup/README.md).

## Parts

| Part                  | Where                       | What                                                                       |
|-----------------------|-----------------------------|----------------------------------------------------------------------------|
| sak-backup            | `sak-backup/src-tauri`      | The window: tauri, its commands, its texts (`sak_backup` of texts.yml)     |
| The pages             | `sak-backup/ui`             | The navigation on the left, the menu of the user at the top right          |
| sak-desktop-shared    | `sak-desktop-shared`        | What the desktop apps share (`DESK-1` in SYSTEM.md)                        |
| The page of the login | `sak-frontend`, `connect/`  | `/connect-desktop`, where the user confirms the connection of the app      |

The sign-in is `DESKTOP-1` to `DESKTOP-3` in [UI-UX.md](../../UI-UX.md) and `SECURITY-3`.

## Privacy

The app connects to the SAK instance of the user only, and opens the browser of the user. Nothing goes to a third
party.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sak-backup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
