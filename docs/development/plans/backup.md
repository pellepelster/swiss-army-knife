---
title: "Plan: Backup and Restore of a Deployment"
linkTitle: "Backup and Restore"
description: "The plan of the backup and restore of a deployment."
weight: 1
---

# Plan: Backup and Restore of a Deployment

**Status:** Built; the integration test (`mise run cli:test:e2e`) passed against a real project on 2026-10-29
**Created:** 2026-10-29
**Last reviewed:** 2026-10-29

## Goal

sakctl makes a backup of a deployment and restores it:

- `sakctl backup <name>` copies all data below `/storage/data/<name>` from the server to the user's machine, as one
  compressed and encrypted file.
- `sakctl restore <name> <file>` puts the data of such a file back on the server of the deployment.
- The backup goes through ssh, the same connection as `sakctl ssh`. No other port, service or third party.
- The SQLite database in the backup is consistent, also while the backend runs and writes to it.
- The server encrypts the backup before it leaves the server. The server has only the public half of the key; only
  the name and the password of the deployment decrypt it ("No local state", ARCHITECTURE.md).

## Where we are

- All data of a deployment is below `/storage/data/<name>` on the data volume:
  - `backend/data/sak.db` (with `sak.db-wal` and `sak.db-shm`, the database is in WAL mode);
  - `backend/data/…` the files of the notes and `backend/data/s3/…` the buckets. The backend writes each file to a
    temporary file and moves it into place (an atomic move), so a copy never has half a file;
  - `ingress/` the certificates and the acme state of caddy, and the access log;
  - `victorialogs/` the access logs as victorialogs keeps them, one directory for each day
    (`partitions/YYYYMMDD/{datadb,indexdb}`), and `vector/` the checkpoints of vector.
- victorialogs (v1.53.0) listens on `127.0.0.1:9428` of the server and has its data at `/victoria-logs-data` in
  its container. Its parts are immutable files, but it merges them and drops old days while it runs, so a plain copy
  of its directory is not consistent.
- The provisioning writes `backend/config.yaml`, `backend/backend.env` and `backend/vector.yaml` on each deploy. They
  come from the deployment and are no data.
- `sakctl ssh <name> -- <command>` runs a command on the server as root, through the container of the tools with the
  derived client key in an ssh agent (`core/ssh.rs`). Its standard input and output are those of sakctl.
- The server had no `sqlite3`, `zstd` and `age`; the role `backend` installs them now, with `curl`.

## 1. What runs on the server

The provisioning (role `backend`) installs `sqlite3`, `zstd`, `age` and `curl`, and the script
`/usr/local/bin/sak-backend` from a template with the paths of the role; `backup` and `restore` are two of its commands
(the others are the maintenance of the server, refer to [FDR-008](../fdr/FDR-008-server-maintenance.md)). sakctl calls
it through ssh. The script is versioned with the role; every deploy provisions, so the server always has the script of
its provisioning.

### `sak-backend backup`

`sak-backend backup <recipient>` writes the encrypted archive to its standard output and nothing else; messages go to
standard error. `<recipient>` is the public half of the backup key (`age1…`), which sakctl gives on each call.

1. Takes a lock (`flock` on `/run/sak-backend.lock`), so only one backup, restore, update or reboot runs at a time.
2. **The logs.** A copy of `vector/` into a staging directory on the data volume (`/storage/data/.backup-<name>`),
   then a snapshot of victorialogs (refer to "The logs" below), its days as hard links in the staging directory.
   victorialogs and vector continue to run.
3. **The database.** `sqlite3 sak.db "VACUUM INTO '<staging>/…/sak.db'"` as the user `sak`. `VACUUM INTO` reads one
   consistent snapshot in one read transaction; the backend continues to write (WAL mode lets readers and writers
   run together). Then `PRAGMA integrity_check` on the copy; a copy that is not `ok` stops the backup. After the
   database, `backend/data` and the other directories (`ingress`, …) as hard links (`cp -al`) in the staging
   directory: the backend writes a file anew and moves it into place, so a link keeps the file of this moment.
4. **The manifest.** `<staging>/backup.json`: the version of the format (`1`), the version of SAK that made the
   backup (`version`, always `latest` until the artifacts have versions,
   [#127](https://github.com/pellepelster/swiss-army-knife/issues/127)), the name, the time (UTC), the image digest
   of the backend and the size of the data before compression.
5. **The archive.** One `tar` stream of the staging directory, compressed with `zstd` (level 3, all cores) and
   encrypted with `age -r <recipient>`, paths relative to `/storage/data/<name>`, with the numeric owner and the
   modes: first `backup.json`, then `backend/data` (the copy of the database in place of `sak.db`), `ingress`,
   `vector` and `victorialogs/partitions/<day>/`. The files of the provisioning (`backend/config.yaml`,
   `backend/backend.env`, `backend/vector.yaml`, the key of victorialogs) are not in it. tar ends with 1 when a file
   changed while it was read (the access log of caddy); that is no failure.
6. Deletes the snapshots of victorialogs (also when the backup fails, `trap`), removes the staging directory and the
   lock.

The database is copied before the files. A row in the copy of the database can only refer to a file that already
existed, and no file is deleted for real (soft delete), so the files of the archive hold every file that the
database refers to. A file that is uploaded during the backup can be in the archive without its row; it is an
orphan, and it does no harm.

### The logs

`vmbackup` and `vmrestore` are tools of VictoriaMetrics; their documentation does not include VictoriaLogs.
VictoriaLogs has its own snapshots of its partitions (refer to "Backup and restore" in the VictoriaLogs
documentation), and SAK uses them:

- **Snapshot.** `POST http://127.0.0.1:9428/internal/partition/snapshot/create` without `partition_prefix` makes a
  snapshot of each day that victorialogs has, and answers their paths as JSON, e.g.
  `["/victoria-logs-data/partitions/20261029/snapshots/20261029203400-18E31A5377B7B4E3"]`. The script replaces
  `/victoria-logs-data` with the path on the server (`backend_victorialogs_data_path`). A snapshot has the
  `datadb/` and `indexdb/` of its day.
- **Delete.** `POST …/internal/partition/snapshot/delete?path=<path>` for each path, after the archive. If a killed
  script does not delete its snapshots, `-snapshotsMaxAge=1d` on victorialogs deletes them.
- **Restore.** The directory of a snapshot is a complete day: `datadb/` and `indexdb/` into
  `partitions/<day>/` of a victorialogs that is stopped, then started.
- **Access.** `-partitionManageAuthKey` protects the endpoints, because other endpoints of the same group detach and
  delete days. The provisioning makes a random key once in `backend_base_path` (mode `0400`, owner `sak`), mounts it
  read-only into the container and gives its path to the flag; the script reads it as root and sends it as
  `authKey`.

Tried with `victoria-logs:v1.53.0` in docker (2026-10-29):

- The snapshot includes the lines received just before it; there is no flush to call first.
- The files of a snapshot are hard links to the parts of the day (the same inode, two links), so a snapshot needs
  no space on the volume until victorialogs merges or drops the parts.
- A new victorialogs with the snapshot copied to `partitions/<day>/` answers the lines of the snapshot.
- `snapshot/delete` answers `204` and the snapshot is gone.

The checkpoint of vector (its position in the access log) is copied before the snapshot, so it is never ahead of
the snapshot. After a restore, vector sends again the lines of the moment between the copy and the snapshot: a line
in the console can occur twice, but no line is lost. A checkpoint copied after the snapshot would lose those lines.

### `sak-backend restore`

Reads an archive from its standard input.

1. Takes the same lock.
2. Extracts the archive into a staging directory on the data volume, `/storage/data/.restore-<name>`. Nothing of the
   deployment changes yet. A full volume, a damaged stream or a stop of sakctl stops the restore here; the script
   removes the staging directory, and the deployment is as before.
3. Checks the staging directory: `backup.json` has format `1` and the name of this deployment, and
   `PRAGMA integrity_check` of `sak.db` is `ok`.
4. Stops all four units (`sak-backend`, `sak-ingress`, `sak-vector`, `sak-victorialogs`).
5. Replaces the data: each directory of the archive (`backend/data`, `ingress`, `victorialogs`, `vector`) moves into
   place (a `rename` on the same volume), the current one moves to `/storage/data/.replaced-<name>`. The
   `victorialogs/` of the archive has only `partitions/<day>/{datadb,indexdb}`; victorialogs makes the rest on its
   start. The files of
   the provisioning stay as they are. Sets the owner `sak:sak` (uid and gid `10000`).
6. Starts the four units, then removes `.replaced-<name>`.

The data volume must have free space for one more copy of the data. The plan does not check this first: a full volume
stops the restore at step 2, before anything changes.

## 2. Compression and encryption

- **Compression on the server.** `zstd` on the server compresses before the data goes through ssh, so less goes
  through the network. Data that is already compressed (photos, recordings) gets no smaller, but costs little time.
- **Encryption on the server.** `sak-backend backup` pipes the compressed stream through
  [age](https://age-encryption.org) (the Debian package `age`) with the recipient that sakctl gives. Only encrypted
  bytes leave the server: the plain data is not in the ssh connection, not in the container of the tools and not in
  sakctl.
- **The key.** A new derived value, purpose `backup`: 32 bytes of the master key as an age X25519 identity. The
  recipient (its public half) goes to the server as an argument; it is public and decrypts nothing. The identity
  stays in sakctl. It joins the table of derived values in ARCHITECTURE.md, "Security". The same name and password
  decrypt the backup on every machine; nothing is stored.
- **A check before the file gets its name.** sakctl writes the encrypted bytes to `<file>.partial` as they are,
  then decrypts the file once with the identity (the `age` crate) and throws the plain bytes away. A backup that
  does not decrypt or is not complete fails, and no file is left. sakctl needs neither zstd nor tar: `sak-backend restore`
  checks `backup.json` and the database on the server.
- **Damage and truncation.** age authenticates each chunk of 64 KiB and marks the last one, so a changed or a
  truncated file fails on decryption, not after the restore.
- **The file.** `~/.sak/backups/<name>/<name>-<UTC time>.age.sak`, e.g.
  `~/.sak/backups/alpha/alpha-20261029T080000Z.age.sak`. sakctl makes the directories (mode `0700`) when they are
  not there. sakctl writes to `<file>.partial` (mode `0600`) and renames it only when the
  script on the server ended with success and the stream is complete. A backup that fails leaves no file that looks
  complete.

## 3. sakctl

### The core

- `core/ssh.rs` gets a second kind of run next to `open_session`: a command on the server whose standard output
  (backup) or standard input (restore) is a stream that the core reads or writes, not the terminal. Standard error
  goes to the log of sakctl. Same container, agent and host key check as the session.
- `core/backup.rs`, a `BackupManager` (the one place that makes and restores backups):
  - `backup(target, key, path)`: runs `sak-backend backup <recipient>`, writes the encrypted bytes to the file, checks it
    by one decryption, answers the path and the size of the file;
  - `restore(target, key, file)`: decrypts the whole file once, so a wrong password or a damaged file fails before
    the server changes, then decrypts it again while it streams it into `sak-backend restore` (refer to "Decryption on
    restore" in the decisions). `sak-backend restore` refuses a backup of another deployment;
  - each line the scripts write to standard error (`sak-backend: …`) goes to the log while they
    run.
- `keys.rs`: `backup_key(name, password)`.

### The command line

- `sakctl backup <name>`: needs the password and the token, like `ssh`. It shows the path of the file.
- `sakctl restore <name> <file> [--yes]`: asks for confirmation (the data of the deployment is replaced), unless
  `--yes` is given; the user types the name of the deployment. The deployment must exist: a restore does not
  create or provision. `sak-backend restore` ends when the backend answers on the server again (5 minutes at most); the
  data from before the restore stays in `/storage/data/.replaced-<name>` when it does not.
- Texts in `cli:` and `shared:` (`en.yml` and `de.yml`), the messages of the core in `shared:`.

### The window

Not in the first steps. Later: "Backup" and "Restore" on the page of a deployment, the restore with the danger zone
like the destroy, both on the core above.

## 4. Restore to a new server

The usual case after a loss of the server: `sakctl create <name>` (same name and password) makes an empty deployment,
then `sakctl restore <name> <file>`. A destroy keeps the data volume unless `--delete-data` is given, so a restore is
for a lost or a deleted volume, or to go back to an earlier state.

## 5. Tests

- `sak-cli` (`cargo test`): the file name, the derived identity (the same for the same name and password, different
  for another purpose), its recipient is what `age-keygen -y` gives for it, a stream that the `age` command encrypts
  to the recipient decrypts with the crate, a truncated or a changed file fails, the manifest check refuses another
  name.
- `ansible` (`mise run backend:test:integration`): the scripts against a container with a data directory and a
  database that a writer changes during the backup. The archive decrypts only with the identity of the
  recipient; the copy of the database passes `integrity_check`; a restore of the archive gives the same rows and
  files. A restore that fails at extraction leaves the data as it was. A victorialogs that receives lines during
  the backup: after the restore it answers the lines of the snapshot, and no snapshot is left on the server.
- `sak-e2e` integration (real cloud project): create, write a note with a random uuid as its text, backup, destroy
  with `--delete-data`, no resource is left, create, no note, restore, the note is there.

## Steps

1. The role `backend`: install `sqlite3`, `zstd`, `age` and `curl`; the key of `-partitionManageAuthKey` and
   `-snapshotsMaxAge=1d` for victorialogs; the script `sak-backend`, its tests.
2. `keys.rs`: the derived backup identity; ARCHITECTURE.md, "Security": its row in the table.
3. `core/ssh.rs`: the run with a stream on standard output or standard input.
4. `core/backup.rs`: `BackupManager`, the encryption, the manifest check, the events.
5. `cli.rs`: `backup` and `restore`, the texts, the parse tests.
6. The integration test in `sak-e2e`.
7. An FDR for backup and restore (behavior and the decisions below), `docs/development/fdr/INDEX.md`; the README.
8. Later: the window.

## Decisions

- **Through ssh, pulled by sakctl.** No backup target on the server, no S3 or other third party, no credentials of a
  storage service. The backups of a deployment are in one directory on the user's machine,
  `~/.sak/backups/<name>/`.
- **Encrypted on the server, to a derived public key.** The plain data never leaves the server, and the server never
  has the secret half of the key. The user has no key to keep beyond the password. Tradeoff: a backup is only as safe
  as the password, and a backup made with an old password needs the old password.
- **Decryption on restore in sakctl.** To decrypt on the server, the secret half of the key would have to go to the
  server for the restore. sakctl decrypts and sends the plain `tar` stream through ssh, so the secret stays on the
  user's machine; the plain data is then in the ssh connection (encrypted by ssh) and in the memory of sakctl. The
  server already has all plain data, so the restore gives it nothing new.
- **`VACUUM INTO`, not a copy of the file.** A copy of `sak.db` while the backend writes can be inconsistent, and the
  WAL file belongs to it. `VACUUM INTO` is one consistent snapshot without a stop of the backend; the copy is also
  compacted. The sqlite3 `.backup` command would also work; `VACUUM INTO` needs no second step for the WAL.
- **Nothing stops during the backup.** The backend, caddy, victorialogs and vector continue to run; the database is
  copied with `VACUUM INTO`, victorialogs with its own snapshots.
- **The snapshots of victorialogs, not `vmbackup`.** `vmbackup` and `vmrestore` are for VictoriaMetrics, not for
  VictoriaLogs, and write to a storage (S3, GCS, Azure, a directory) instead of a stream. The snapshots of
  VictoriaLogs need only its http api and go into the same `tar` stream as the rest.
- **Restore through a staging directory.** The data is replaced only after the archive is complete and checked; a
  restore that fails before that changes nothing.
- **The files of the provisioning are not in the backup.** The deploy writes them from the deployment; a restore keeps
  those of the current provisioning.
- **A restore only into the same name.** `backup.json` must have the name of the deployment; the paths and the key
  contain the name.
- **No backups on a schedule.** A backup runs only when the user starts it with sakctl.

## Open Questions

- **FDR-002 (database encryption).** With an encrypted database, `sqlite3` on the server must be a build with the
  same cipher and needs the key for `VACUUM INTO`. The backup could then copy the encrypted pages as they are.
- **A newer backup on an older backend.** The backend migrates an older database on its start (Liquibase). A backup
  from a newer backend has changesets that the older one does not know. The restore does not compare `version` yet;
  [#128](https://github.com/pellepelster/swiss-army-knife/issues/128) adds the check after #127.
