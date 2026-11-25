---
title: "Status"
description: "The maintenance page with the health of the system and what the storages keep."
weight: 48
---

# Status

A maintenance page, no app (refer to [the architecture of the components](INDEX.md#the-components)). Adds to it and to
[SYSTEM.md](../SYSTEM.md).

## Purpose

Shows the health of the system and its checks ([HEALTH.md](HEALTH.md)), and what every storage keeps: the files of the
apps, the buckets, the backups of restic and the database.

## Data

None stored. The counts of the storages are kept in memory.

## API

`GET /api/v1/status`: the health of the whole system (`HEALTH-3`) and what every storage keeps, the largest first, with
the total; for the role `admin` only.

## Decisions

- `STATUS-1` **The storages are counted in the background.** The backend counts what every storage keeps when it
  starts and every 15 minutes. An ask answers the last count and counts nothing, so the page does not wait for a
  listing of a bucket.
- `STATUS-2` **The backups count where they are kept now.** The repositories of restic are counted in the storage the
  settings point to (`BACKUP-1`); what stayed in the other one after a switch is not counted.
