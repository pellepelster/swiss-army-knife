---
title: "FDR-004: Maintenance Cleanup"
linkTitle: "Maintenance Cleanup"
description: "A maintenance task that marks unused data as deleted and removes soft-deleted data after a retention period."
weight: 4
---

# FDR-004: Maintenance Cleanup

**Status:** Planned
**Last reviewed:** 2026-10-29

## Overview

The backend deletes nothing for real: a delete only sets `deleted_at` (refer to "Persistence" in
[ARCHITECTURE.md](../ARCHITECTURE.md)). Some data is never deleted at all: an uploaded file that no note refers to stays for
ever, and a user can upload files of 10 MB in a loop (issue #2, finding M-02). This feature adds a maintenance task
that finds data nothing uses any more, marks it as deleted, and after a retention period removes soft-deleted data
for real.

## Behavior

- The maintenance task runs in the backend at a fixed interval. An admin can also start it through the api.
- It soft-deletes files that no live note refers to and that are older than a grace period, so an upload in progress
  is not removed.
- It purges rows whose `deleted_at` is older than the retention period: it removes them for real, and with a file
  row the content of the file, when no other row has the same sha256.
- A purged bucket name can be taken again.
- Each run writes one log line with the number of rows it soft-deleted and purged. The log has no file names, user
  data or addresses.
- An admin can see the time and the result of the last run.

## Design Decisions

### 1. Two steps: soft-delete, then purge

**Decision:** Unused data is first soft-deleted, and purged only after the retention period.
**Why:** A mistake in the "unused" rule can be undone during the retention period. The purge works on one rule only:
`deleted_at` older than the retention period.
**Tradeoff:** Data stays on the disk for the retention period after it is deleted.

### 2. The maintenance task is the only code that purges

**Decision:** The purge is an exception to "no row is ever deleted for real", and it is in one service only.
**Why:** All other code keeps the soft-delete rule. One place to review for real deletes.
**Tradeoff:** `AGENT.md` must name this exception.

### 3. Content is shared by its sha256

**Decision:** The content of a file is removed only when no row, live or soft-deleted, has the same sha256.
**Why:** Two uploads of the same content share it. A purge of one row must not remove the content of another.
**Tradeoff:** One more query for each purged file.

## Related

- **ADRs:** none
- **FDRs:** FDR-002 (the purged data is also in the encrypted database)

## Open Questions

- How long are the grace period and the retention period, and are they set in the configuration?
- Is there a quota for each user, as issue #2 suggests, and what happens at the limit?
- Does the task also purge the other soft-deleted entities (notes, labels, redirects, buckets), or only files first?
- Does the purge run `VACUUM`, so that SQLite gives the space back to the disk?
- Does a deleted bucket keep its content in `sak-s3` until the purge, and does the purge remove it?
