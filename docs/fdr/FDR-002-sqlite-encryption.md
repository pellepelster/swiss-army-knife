---
title: "FDR-002: SQLite Database Encryption"
linkTitle: "SQLite Encryption"
description: "The encryption of the complete database file at rest."
weight: 2
---

# FDR-002: SQLite Database Encryption

**Status:** Planned
**Last reviewed:** 2026-10-29

## Overview

The backend keeps its data in one SQLite database file on the data volume of the server. Today all data in it is
plain text: the secrets (the S3 keys of the buckets, the signing key of the login), email addresses and the content of
the apps. A separate encryption of the secrets was removed in favor of this feature. This feature encrypts the complete database file, so that all data in it is encrypted at rest, as
[AGENT.md](../../AGENT.md) requires.

## Behavior

- The database file is encrypted on disk. Its write-ahead log and other temporary files are also encrypted.
- A person who has the file, a copy of the data volume or a backup, but not the key, cannot read data from it.
- The backend opens the database with the key when it starts.
- When the key is missing or wrong, the backend does not start. It logs an error that names the problem, but not
  the key.
- The users of the apps see no change.

## Design Decisions

### 1. Encrypt the complete file

**Decision:** SAK encrypts each page of the database file, not single columns.
**Why:** With the complete file encrypted, a new table or column is encrypted without more work. A developer cannot
forget to encrypt a field.
**Tradeoff:** The standard SQLite driver has no encryption. SAK must use a SQLite build with an encryption extension.

### 2. No separate encryption of the secrets

**Decision:** The secrets are plain in their tables (the signing key in `settings`, the secret key of a bucket in
`s3_buckets`); the encryption of the database file protects
them with all other data.
**Why:** One key and one mechanism instead of two. A separate encryption of single columns protected only the
secrets and left all other user data plain.
**Tradeoff:** A secret is plain in an export or a dump of the decrypted database.

## Related

- **ADRs:** none
- **FDRs:** none

## Open Questions

- Which encryption extension does SAK use (for example SQLCipher or SQLite3 Multiple Ciphers), and which JDBC
  driver supports it?
- Where does the key come from: a value in `backend.env` that sakctl writes, derived from the deployment password,
  or a key that the server keeps on its own?
- How does SAK change the key (key rotation)?
- How does SAK encrypt an existing database that is not encrypted, on the first start of the new version?
- Does SAK also encrypt the complete data volume (for example with LUKS)? The volume also holds data that is not in
  the database, for example the logs and the files of the ingress.
