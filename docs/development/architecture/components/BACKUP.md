---
title: "Backup"
description: "The backups of the user with restic: the repositories the instance keeps and sakbackup, the desktop app."
weight: 50
---

# Backup

Adds to [the architecture of the components](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

The backups of the user, made with restic on the computer of the user, into repositories the SAK instance keeps.

## Data

- `BACKUP-1` **A repository** is a repository of restic that the SAK instance keeps on its data volume and serves
  with the REST protocol of restic, below `api.<base domain>/restic/`. It has a name, unique among the repositories
  of its owner, and belongs to its owner only.
- `BACKUP-2` **One token per repository.** The instance makes a token when it creates a repository; the token is the
  only login of the repository, and whoever has it reads and writes it. The api answers the token only when asked for
  it alone.
- `BACKUP-3` **The password of a backup is never on the instance.** restic encrypts a repository on the computer of
  the user with a password the user gives for each run; the instance keeps only what restic has encrypted.
- `BACKUP-4` **The backups of the instance leave the repositories out.** A backup of sakadmin (`SAKADM-8`) has the data
  of the apps, not the repositories of restic.
- `BACKUP-5` **A backup** is what restic backs up and where to: a repository of the user and paths of one computer,
  named by its host name. It belongs to its owner only. The app changes and runs only the backups of the computer it
  runs on; it shows the others without changing them.
- `BACKUP-6` **The paths of a backup are kept on the instance**, as user data: only their owner sees them, and they go
  nowhere else.
- `BACKUP-7` **A first repository.** When a user signs in who never had a repository of their own, the instance makes
  them one named `my-backups`; a deleted repository does not come back.
- `BACKUP-8` **How long snapshots are kept** is a setting of the backup plan, kept on the instance with it: how
  many of the last, daily, weekly, monthly and yearly snapshots stay. After a backup, the app removes the snapshots
  of that backup plan the setting no longer keeps; without a setting every snapshot stays.

## API

Create repositories and list them, and read the token of one; create, list, read and change backups; for every
signed in user, their own only.

## Parts

| Part                  | Where                       | What                                                                       |
|-----------------------|-----------------------------|----------------------------------------------------------------------------|
| sakbackup            | `sak-backup/src-tauri`      | The window (tauri, its commands), the command line and the core they share (`DESK-2`); its texts are `sak_backup` of texts.yml |
| The pages             | `sak-backup/ui`             | The navigation on the left, the menu of the user at its bottom             |
| sak-desktop-shared    | `sak-desktop-shared`        | What the desktop apps share (`DESK-1` in SYSTEM.md)                        |
| The page of the login | `sak-frontend`, `connect/`  | `/connect-desktop`, where the user confirms the connection of the app      |
| The repositories      | `io.pelle.sak.backend.restic`, `sak-model` (`restic/`) | Their api, their REST backend, their table        |

The sign-in is `DESKTOP-1` to `DESKTOP-3` in [UI-UX.md](../../UI-UX.md) and `SECURITY-3`.

## Privacy

The app connects to the SAK instance of the user only, and opens the browser of the user. Nothing goes to a third
party.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sakbackup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
