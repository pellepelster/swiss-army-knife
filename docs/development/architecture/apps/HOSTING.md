---
title: "Static Hosting"
description: "The app that serves websites from S3 buckets at their own domains."
weight: 10
---

# Static Hosting

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Serves websites from S3 buckets. A user uploads the files of a site with any S3 client; the backend serves them at the
domains of the bucket.

## Data

- `HOSTING-1` **A bucket** has a name, a key pair of its own and the domains it is served at. Every bucket is also
  served at `<name>.<base domain>`.
- `HOSTING-2` **The objects** of a bucket are files on the data volume, below a directory of the bucket.
- `HOSTING-3` **The statistics** of a bucket (number of objects, bytes) are counted in the background and kept; a
  write through the S3 api drops them, so they are counted again.

## API

- `HOSTING-4` Create, change the domains of, and delete a bucket; read the buckets with their statistics.
- `HOSTING-5` Read the key pair of a bucket, only on its own endpoint.
- `HOSTING-6` The S3 api takes requests signed with the key pair of a bucket, for that bucket only.

## Settings

None.

## External systems

None. The health of the domains asks name servers directly (refer to
[the architecture of the apps](INDEX.md#shared-parts)).

## Decisions

- `HOSTING-7` **One key pair for each bucket.** A key pair accesses its own bucket only; a leaked key pair gives away
  one site.
- `HOSTING-8` **The secret key stays in the backend.** It is stored with the bucket, but is part of no answer except
  the endpoint of the key pair.
- `HOSTING-9` **A deleted bucket keeps its name and its objects** until it is purged (refer to
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md)); it is served nowhere and its key pair is refused.
- `HOSTING-10` **A domain of a bucket** is no domain of the apps and not redirected (refer to [the architecture of the
  apps](INDEX.md#shared-parts)).
- `HOSTING-11` **A bucket that redirects lead to is not deleted.** The delete names the redirects; the user deletes
  them or changes their target first. A deleted bucket keeps its name (`HOSTING-9`), so a redirect to it could never
  lead anywhere again. `DomainRegistry` tells the buckets of the redirects.

## Related

- **FDRs:** [FDR-001](../../fdr/FDR-001-dns-mode.md) (the domains of the buckets by the dns mode),
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md)
