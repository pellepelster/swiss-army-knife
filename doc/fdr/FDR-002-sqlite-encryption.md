# FDR-002: SQLite Database Encryption

**Status:** Planned
**Last reviewed:** 2026-10-28

## Overview

The backend keeps its data in one SQLite database file on the data volume of the server. Today only the secrets in
the database are encrypted. All other data, for example email addresses and the content of the apps, is plain text
in the file. This feature encrypts the complete database file, so that all data in it is encrypted at rest, as
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

### 2. Keep the encryption of the secrets

**Decision:** The secrets stay encrypted in their table (`SecretService`), also in the encrypted database.
**Why:** Two layers. A secret stays encrypted in an export or a dump of the decrypted database.
**Tradeoff:** A secret is encrypted twice.

## Related

- **ADRs:** none
- **FDRs:** none

## Open Questions

- Which encryption extension does SAK use (for example SQLCipher or SQLite3 Multiple Ciphers), and which JDBC
  driver supports it?
- Where does the key come from: a new value next to `sak.secret_keys` in the server configuration, or a key derived
  from the secret keys?
- How does SAK change the key (key rotation)?
- How does SAK encrypt an existing database that is not encrypted, on the first start of the new version?
- Does SAK also encrypt the complete data volume (for example with LUKS)? The volume also holds data that is not in
  the database, for example the logs and the files of the ingress.
