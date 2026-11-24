---
title: "Backup"
description: "The backups of the user with restic: the repositories the deployment keeps and sakbackup, the desktop app."
weight: 50
---

# Backup

Adds to [the architecture of the components](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

The backups of the user, made with restic on the computer of the user, into repositories the deployment keeps.

## Data

- `BACKUP-1` **The deployment keeps the repositories of restic.** A repository is a repository of restic that the
  deployment keeps on its data volume, or in the bucket of the S3 storage while the backups are switched to it in the
  settings ("Restic backups"), and serves with the REST protocol of restic, below `api.<base domain>/restic/`. It has a
  name, unique among the repositories of its owner, and belongs to its owner only. The switch is one for all
  repositories: a repository is read where the switch points to, and its files are not moved when the switch changes.
- `BACKUP-2` **One token per repository.** The deployment makes a token when it creates a repository; the token is the
  only login of the repository, and whoever has it reads and writes it. The api answers the token only when asked for
  it alone.
- `BACKUP-3` **A repository password is never on the deployment.** restic encrypts a repository on the
  computer of the user with a password the user gives for each run; the deployment keeps only what restic has encrypted.
- `BACKUP-4` **A backup leaves the repositories out.** A backup of sakadmin (`SAKADMIN-6`) has the
  data of the apps, not the repositories of restic.
- `BACKUP-5` **A backup plan belongs to one computer.** A backup plan is what restic backs up and where to: a repository
  of the user and paths of one computer, named by its host name. It belongs to its owner only. The app changes and runs
  only the backup plans of the computer it runs on; it shows the others without changing them. A run of a backup plan
  makes a snapshot.
- `BACKUP-6` **Backup plans stay on the deployment.** Their paths and excludes are kept there as user data: only their
  owner sees them, and they go nowhere else.
- `BACKUP-7` **A first repository.** When a user signs in who never had a repository of their own, the deployment makes
  them one named `my-backups`; a deleted repository does not come back.
- `BACKUP-8` **A backup plan sets how long snapshots stay.** How long snapshots are kept is a setting of the backup
  plan, kept on the deployment with it: how many of the last, daily, weekly, monthly and yearly snapshots stay. After a
  run, the app deletes the snapshots of that backup plan the setting no longer keeps; without a setting every snapshot
  stays.
- `BACKUP-9` **A deleted repository takes its backup plans.** Its owner deletes a repository after a confirmation that
  names it. Its backup plans, of every computer, and their paths are deleted with it, and its token is refused from
  then on. Its files stay until it is purged (`MODEL-10`). A deleted repository does not come back.

## API

Create repositories, list them and delete them, and read the token of one; create, list, read, change and delete backup
plans; for every signed in user, their own only.

## Parts

| Part                  | Where                       | What                                                                       |
|-----------------------|-----------------------------|----------------------------------------------------------------------------|
| sakbackup            | `sak-backup/src-tauri`      | The window (tauri, its commands), the command line and the core they share (`DESKTOP-6`); its texts are `sak_backup` of texts.yml |
| The pages             | `sak-backup/ui`             | The navigation on the left, the menu of the user at its bottom             |
| sak-desktop-shared    | `sak-desktop-shared`        | What the desktop apps share (`DESKTOP-5` in SYSTEM.md)                        |
| The page of the login | `sak-frontend`, `connect/`  | `/connect-desktop`, where the user confirms the connection of the app      |
| The repositories      | `io.pelle.sak.backend.restic`, `sak-model` (`restic/`) | Their api, their REST backend, their table        |

The sign-in is `DESKTOP-1` to `DESKTOP-3` in [UI-UX-DESKTOP.md](../../ui-ux/UI-UX-DESKTOP.md), `SECURITY-3` and `SECURITY-4`.

## Privacy

The app connects to the deployment of the user only, and opens the browser of the user. Nothing goes to a third
party.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sakbackup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
