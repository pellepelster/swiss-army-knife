---
title: "Backups (Preview)"
linkTitle: "Backups"
description: "The restic repositories that a SAK instance keeps for each user, and Restic Backup, the desktop app that makes the backups. A preview."
weight: 6
---

# Backups (preview)

A SAK instance keeps [restic](https://restic.net) repositories for you: places for the backups of your own computer.
This is a preview. The page **Backups** of the web app shows your repositories; the desktop app Restic Backup
(`sakbackup`) makes the backups.

These are not the backups of the SAK instance itself. For those, read
[Back up and restore](../manage.md#back-up-and-restore).

## Your repositories

When you sign in, your SAK instance makes you a first repository, `my-backups`. The page **Backups** lists your
repositories with their size, and copies the address of a repository. Only you see your repositories. A repository
can not be deleted yet.

## Restic Backup

Restic Backup is a preview. The commands below download it from the
[release page](https://github.com/pellepelster/swiss-army-knife/releases), check its SHA-256 checksum and install it.
Each file has its cosign signature (`.sigstore.json`) next to it; verify it as for sakadmin
([Check the signature](../installation.md#check-the-signature)), with the name of the Restic Backup file.

On Linux (x86_64), pick one of four. The program, for any distribution with WebKitGTK 4.1:

{{< install app="sakbackup" platform="linux" >}}

A deb package for Debian and Ubuntu:

{{< install app="sakbackup" platform="deb" >}}

An rpm package for Fedora and openSUSE (`sudo zypper install` instead of `dnf` there):

{{< install app="sakbackup" platform="rpm" >}}

An AppImage, which brings everything it needs; start `~/.local/bin/sakbackup.AppImage`:

{{< install app="sakbackup" platform="appimage" >}}

On macOS, then start `~/.local/bin/sakbackup`:

{{< install app="sakbackup" platform="macos" >}}

On Windows, then start `sakbackup.exe` in `%LOCALAPPDATA%\Programs\sakbackup`:

{{< install app="sakbackup" platform="windows" >}}

- Restic Backup is one program, `sakbackup`. Without a command it opens the window; `sakbackup --help` lists the
  commands. On Windows, `sakbackup.exe` is the window and `sakbackup-cli.exe` is the command line.
- A backup plan can leave things out, for example caches or `node_modules`. In the form of a plan, **Edit
  excludes…** opens a list of patterns of restic, one a line, with `#` for a comment. Presets for Linux, macOS and
  Windows add common caches, build output and scratch files; a preset is copied into the plan, so you can change it.
  On the command line: `sakbackup plan set <plan> --preset caches --exclude node_modules`, `--exclude-file <file>`
  and `--exclude-caches`.
- While the window is open, Restic Backup has an icon in the tray of the panel. Its menu shows the window again and
  quits. During a backup, the icon shows the progress as a bar. If you close the window while a backup or a restore
  runs, it goes to the tray and the run goes on. Quit asks first and stops the run. A desktop without a tray, such
  as GNOME without an extension, has no icon, and closing the window works as before.
- Restic Backup needs [restic](https://restic.net) on your computer. At its start it looks for restic and shows the
  version it found; without restic it shows how to get it and stops.
- Restic Backup is not signed for macOS and Windows, so they warn on the first start.
- Restic Backup updates only when you ask: click **Check for updates** at the bottom of the window, or run
  `sakbackup update`. It downloads the newest release from GitHub, verifies its checksum and its signature, and
  replaces itself; an AppImage replaces its file. Installed from a deb or an rpm, it only tells you that there is a
  new release: install its package the same way. `sakbackup update --check` only compares the versions. On
  Windows, the command is `sakbackup-cli.exe update`.

## Your data stays yours

restic encrypts a backup on your computer, with a password that only you have. Your SAK instance keeps only the
encrypted data and never gets that password. A backup of the SAK instance does not hold the repositories.
