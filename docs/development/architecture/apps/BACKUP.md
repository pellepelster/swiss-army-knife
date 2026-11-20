---
title: "Backup"
description: "The backups of the user with restic: the repositories of the instance and sak-backup, the desktop app that runs restic."
weight: 50
---

# Backup

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

The backups of the user, made with restic on the computer of the user. The SAK instance keeps where the backups go,
never the password of a backup.

## Data

- `BACKUP-1` **A repository** is where restic keeps backups: its name, its url, the environment and the options
  restic needs to reach it, and its limits of upload and download. Its name is unique among the repositories of its
  owner.
- `BACKUP-2` **A repository is local or s3.** A local repository is an absolute path on the computer that runs
  restic; an s3 repository is a bucket of an s3 service. The url holds no login; the login is in the environment.
- `BACKUP-3` **Only what restic needs to reach the repository.** The environment and the options take only the names
  of their kind of repository, and nothing that makes restic start a program. A repository of another user can not
  run anything on the computer of the user.
- `BACKUP-4` **The password of a backup is never stored.** Neither the instance nor its api takes the password
  restic encrypts a repository with. The user gives it for each run, on the computer that runs restic.
- `BACKUP-5` **The secrets of a connection** (e.g. the secret key of an s3 bucket) are stored, and the api answers
  them only when asked for them alone, never in a list or in a repository.

## Sharing

- `BACKUP-6` **A shared repository** is seen by all users. Whoever sees it gets the secrets of its connection too,
  so they can back up to it; sharing a repository shares its secrets. Only its owner shares it. A local repository
  can not be shared: its path is a folder of each computer.

## API

List, create, change and read repositories, and read the secrets of the connection of one; for every signed in
user. A user sees their own repositories and the shared ones of the others.

## Settings

None.

## External systems

The instance contacts none. The app on the computer of the user contacts the s3 service of an s3 repository, with
the secrets of its connection: that service sees the IP address of the user and the encrypted backups.

## Parts

| Part                  | Where                       | What                                                                       |
|-----------------------|-----------------------------|----------------------------------------------------------------------------|
| sak-backup            | `sak-backup/src-tauri`      | The window: tauri, its commands, its texts (`sak_backup` of texts.yml)     |
| The pages             | `sak-backup/ui`             | The navigation on the left, the menu of the user at the top right          |
| sak-desktop-shared    | `sak-desktop-shared`        | What the desktop apps share (`DESK-1` in SYSTEM.md)                        |
| The page of the login | `sak-frontend`, `connect/`  | `/connect-desktop`, where the user confirms the connection of the app      |
| The repositories      | `io.pelle.sak.backend.restic`, `sak-model` (`restic/`) | The api of the repositories and their tables      |

The sign-in is `DESKTOP-1` to `DESKTOP-3` in [UI-UX.md](../../UI-UX.md) and `SECURITY-3`.

## Privacy

The app connects to the SAK instance of the user and to the s3 services of the repositories of the user (refer to
[External systems](#external-systems)), and opens the browser of the user.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sak-backup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
