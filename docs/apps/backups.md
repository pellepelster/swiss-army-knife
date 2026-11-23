---
title: "Backups (Preview)"
linkTitle: "Backups"
description: "The restic repositories that a SAK instance keeps for each user, and SAK Backup, the desktop app that makes the backups. A preview."
weight: 6
---

# Backups (preview)

A SAK instance keeps [restic](https://restic.net) repositories for you: places for the backups of your own computer.
This is a preview. The page **Backups** of the web app shows your repositories; the desktop app SAK Backup
(`sakbackup`) makes the backups.

These are not the backups of the SAK instance itself. For those, read
[Back up and restore](../manage.md#back-up-and-restore).

## Your repositories

When you sign in, your SAK instance makes you a first repository, `my-backups`. The page **Backups** lists your
repositories with their size, and copies the address of a repository. Only you see your repositories. A repository
can not be deleted yet.

## SAK Backup

SAK Backup is a preview. Download the zip file for your computer from the
[release page](https://github.com/pellepelster/swiss-army-knife/releases): `sak-backup-linux-x86_64-<version>.zip`,
`sak-backup-macos-universal-<version>.zip` or `sak-backup-windows-x86_64-<version>.zip`. Each zip file has its
checksum (`.sha256`) and its cosign signature (`.sigstore.json`) next to it; verify them as for sakadmin
([Check the signature](../installation.md#check-the-signature)).

- The zip file holds one program, `sakbackup`. Without a command it opens the window; `sakbackup --help` lists the
  commands. On Windows, `sakbackup.exe` is the window and `sakbackup-cli.exe` is the command line.
- A backup plan can leave things out, for example caches or `node_modules`. In the form of a plan, **Edit
  excludes…** opens a list of patterns of restic, one a line, with `#` for a comment. Presets for Linux, macOS and
  Windows add common caches, build output and scratch files; a preset is copied into the plan, so you can change it.
  On the command line: `sakbackup plan set <plan> --preset caches --exclude node_modules`, `--exclude-file <file>`
  and `--exclude-caches`.
- SAK Backup needs [restic](https://restic.net) on your computer. At its start it looks for restic and shows the
  version it found; without restic it shows how to get it and stops.
- SAK Backup is not signed for macOS and Windows, so they warn on the first start.
- SAK Backup updates only when you ask: click **Check for updates** at the bottom of the window, or run
  `sakbackup update`. It downloads the newest release from GitHub, verifies its checksum and its signature, and
  replaces itself. `sakbackup update --check` only compares the versions. On Windows, the command is
  `sakbackup-cli.exe update`.

## Your data stays yours

restic encrypts a backup on your computer, with a password that only you have. Your SAK instance keeps only the
encrypted data and never gets that password. A backup of the SAK instance does not hold the repositories.
