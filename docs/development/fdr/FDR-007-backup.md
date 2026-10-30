---
title: "FDR-007: Backup and Restore"
linkTitle: "Backup and Restore"
description: "A backup of all data of a deployment, encrypted on the server, and its restore."
weight: 7
---

# FDR-007: Backup and Restore

**Status:** Active
**Last reviewed:** 2026-10-29

## Overview

A deployment keeps all its data on the data volume of its server. sakctl makes a backup of this data on the user's
machine, and restores it into the deployment of the same name, for example after the server or the data volume is
lost.

## Behavior

- **Backup.** `sakctl backup <name>` writes all data of the deployment to one file,
  `~/.sak/backups/<name>/<name>-<UTC time>.age.sak`: the database, the files of the notes, the buckets, the state of
  the ingress (its certificates) and the logs. The configuration that a deploy writes is not in it.
- **Nothing stops for a backup.** The apps and the logs stay available while it runs.
- **Encrypted before it leaves the server.** Only the name and the password of the deployment decrypt a backup. A
  backup made with another password needs that password.
- **Only complete backups.** sakctl decrypts a new backup once before it gives the file its name. A backup that
  fails, is damaged or is not complete leaves no file.
- **Restore.** `sakctl restore <name> <file>` replaces all data of the deployment with the data of the backup, after
  the user types the name of the deployment (`--yes` without asking). The deployment must exist, for example created
  anew with the same name and password. The apps are not available while the data is replaced.
- **A restore that cannot succeed changes nothing.** A backup that does not decrypt, is damaged, is of another
  deployment or has a damaged database is refused before the data of the deployment changes.
- **One at a time.** A backup or restore refuses to start while another one runs on the server.

## Design Decisions

### 1. Through ssh, pulled by sakctl

**Decision:** sakctl starts the backup through ssh and writes the file on the user's machine.
**Why:** The same connection as the provisioning and `sakctl ssh`, verified by the derived host key. No other port,
service, storage provider or credentials.
**Tradeoff:** A backup is only as recent as the last `sakctl backup`; there are no backups on a schedule.

### 2. Encrypted on the server, to a key derived from the password

**Decision:** The server compresses and encrypts the backup with age to the public half of a key that sakctl derives
from the password. The secret half stays in sakctl; a restore decrypts in sakctl and sends the data through ssh.
**Why:** The plain data never leaves the server, and the server never has the key that decrypts it. The user has no
key to keep beyond the password ("No local state").
**Tradeoff:** A backup is only as safe as the password.

### 3. A consistent copy without a stop

**Decision:** The database is copied with `VACUUM INTO` while the backend runs, the logs with the snapshots of
victorialogs, the files as hard links of one moment, the database before the files.
**Why:** The apps stay available, and every file that the database refers to is in the backup (files are never
changed in place, and rows are deleted only softly).
**Tradeoff:** A file uploaded during the backup can be in it without its row. After a restore, a few lines of the
access logs can show two times.

### 4. A restore through a staging directory

**Decision:** The server extracts and checks the backup next to the data first, then stops the deployment, replaces
the directories and starts it again.
**Why:** A restore that fails before the replacement changes nothing.
**Tradeoff:** The data volume needs free space for one more copy of the data.

### 5. A restore only into the same name

**Decision:** The server refuses a backup of another deployment.
**Why:** The paths on the server and the key of the backup depend on the name.

### 6. A restore only into the same or a newer version

**Decision:** A backup has the version of the server that wrote it. The server refuses a backup of a newer version
than its own, and tells the user to deploy that version first. A backup or a server of no known version (`latest`)
is restored with a warning.
**Why:** The backend migrates an older database on its start, but does not know the changes of a newer one, and can
fail or damage the data.
**Tradeoff:** A backup of a newer version cannot go back to an older version.

## Related

- **ADRs:** none
- **FDRs:** [FDR-002](FDR-002-sqlite-encryption.md) (with an encrypted database, the copy of the database needs its
  key)

## Open Questions

- The window has no backup or restore yet.
