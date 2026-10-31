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

## Parts

| Part | What it is |
|---|---|
| `io.pelle.sak.backend.hosting` | The buckets (`S3BucketService`), their statistics, their health, the routes of the app |
| `sak-s3` | The S3 api at `api.<base domain>` and the storage of the objects on the data volume |
| `sak-frontend/src/app/hosting/` | The list of the buckets and the page of a bucket with its tabs |
| `s3_buckets`, `s3_buckets_domains` | A bucket with its key pair, and the domains it is served at |

## Data

- **A bucket** has a name, a key pair of its own and the domains it is served at. Every bucket is also served at
  `<name>.<base domain>`.
- **The objects** of a bucket are files on the data volume, below a directory of the bucket.
- **The statistics** of a bucket (number of objects, bytes) are counted in the background and kept; a write through
  the S3 api drops them, so they are counted again.

## API

- Create, change the domains of, and delete a bucket; read the buckets with their statistics.
- Read the key pair of a bucket, only on its own endpoint.
- The S3 api takes requests signed with the key pair of a bucket, for that bucket only.

## Settings

None.

## External systems

None. The health of the domains asks name servers directly (refer to
[the architecture of the apps](INDEX.md#shared-parts)).

## Decisions

- **One key pair for each bucket.** A key pair accesses its own bucket only; a leaked key pair gives away one site.
- **The secret key stays in the backend.** It is stored with the bucket, but is part of no answer except the
  endpoint of the key pair.
- **A deleted bucket keeps its name and its objects** until it is purged (refer to
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md)); it is served nowhere and its key pair is refused.
- **A domain of a bucket** is no domain of the apps and not redirected (refer to [the architecture of the apps](INDEX.md#shared-parts)).

## Related

- **FDRs:** [FDR-001](../../fdr/FDR-001-dns-mode.md) (the domains of the buckets by the dns mode),
  [FDR-004](../../fdr/FDR-004-maintenance-cleanup.md)
