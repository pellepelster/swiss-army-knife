---
title: "Backups (Preview)"
linkTitle: "Backups"
description: "The restic repositories that a SAK instance keeps for each user. A preview: the desktop app that makes the backups is not released yet."
weight: 6
---

# Backups (preview)

A SAK instance keeps [restic](https://restic.net) repositories for you: places for the backups of your own computer.
This is a preview. The page **Backups** of the web app shows your repositories; the desktop app that makes the
backups, SAK Backup, is not released yet.

These are not the backups of the SAK instance itself. For those, read
[Back up and restore](../manage.md#back-up-and-restore).

## Your repositories

When you sign in, your SAK instance makes you a first repository, `my-backups`. The page **Backups** lists your
repositories with their size, and copies the address of a repository. Only you see your repositories. A repository
can not be deleted yet.

## Your data stays yours

restic encrypts a backup on your computer, with a password that only you have. Your SAK instance keeps only the
encrypted data and never gets that password. A backup of the SAK instance does not hold the repositories.
