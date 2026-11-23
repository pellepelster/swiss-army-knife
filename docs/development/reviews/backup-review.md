---
title: "Review: backups with restic"
linkTitle: "Backups"
description: "The review of the backups with restic (the REST backend of the deployment, the backup plans, sakbackup with its window and command line) for security, data safety, resource usage and test coverage."
weight: 10
---

# Review: backups with restic

**Created:** 2026-11-21

**Checked again:** 2026-11-23, every finding listed is still open but three items of `BK-22`, fixed with `DA-32`
and `DA-33` of [the review of the desktop apps](desktop-apps-review.md) and with `c6233dd0`; the fixed ones (`BK-1`,
`BK-2`, `BK-3`, `BK-5`) are in the history of git. Since the review the repositories can also be kept in the S3
storage (`S3ResticFileStorage`): what is said of the volume below holds for the volume only. `BK-18` is withdrawn: `ResticStore.save` checks and moves under the lock of the repository, so
the second of two writes of a config is refused.

Scope: the repositories of restic and their REST backend (`io.pelle.sak.backend.restic`, `sak-model` `restic/`, the
route `/restic` of caddy), how a backup of the deployment treats them (`sak-backend` of the role backend), the web app
(`sak-frontend/projects/backups/`), and sakbackup (`sak-backup/src-tauri`, `sak-backup/ui`) with what it uses of
sak-desktop-shared. The questions: does anything leak or stay unencrypted; can data be lost; is everything streamed,
with memory that does not grow with the data; what do the tests not cover.

How sure a finding is: **reproduced** (run against restic 0.16.4 or read in the code by the author of this review),
**read** (traced in the code by a reviewer, not run), **suspected** (depends on how restic, caddy or the webview
behave).

What is good: a token is 256 random bits, compared in constant time, and the login must be the repository of the path,
so a token never reaches another repository; the names of files are a fixed set of types and 64 hex digits, no path
leaves a repository; an upload is streamed with its sha-256 computed on the way, the one of a file named by its hash
checked before it shows (on the volume a staged file moved into place, in the S3 storage a multipart upload completed
only then), and a failed one is removed or aborted; a download and its ranges are
streamed; the token and the password reach restic in its environment only, never in arguments or the page; restic hides
the token in what it prints; the page has no `innerHTML` and a strict CSP; a password is kept only after restic took it;
the window asks before a restore in place and before a snapshot is deleted; `/restic` is behind TLS only.

Only the open findings are listed.

## Must fix: data can be lost

### `BK-7` Whoever has the token of a repository can delete all of it (read)

`DELETE` is allowed on every file of a repository, the token is on the computer of the user and can not be changed.
Malware there, or a leaked token, removes every backup; nothing on the server keeps what was deleted. Append-only was
left for later when the REST backend was designed; deleting snapshots and the expiry (`BACKUP-8`) now need deletes.

Fix, to decide: the server keeps deleted files for some days before it removes them (locks apart); and a token can be
replaced. Until then the risk is accepted in [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md).

## Should fix

### `BK-9` No quota: one user fills the volume for all (read)

The only limit is the free space of the volume (2 GiB left, `WriteGuard`), and only while the repositories are on the
volume: in the S3 storage there is none, a user's backups grow the bill of the bucket. On the volume, one user's
backups make every upload of everyone answer 507; the buckets and the files share the volume. Quotas were left for
later.

Fix: a limit of bytes per repository or user, checked with the usage and the length of an upload.

### `BK-10` A restore from the command line overwrites in place without asking (read)

`sakbackup restore <plan>` has `--target /` as its default and asks nothing; the window asks first.

Fix: the command line needs `--target` or `--in-place`.

### `BK-12` The computer is told by its host name only (suspected for the locks)

Two computers of one name show, change and run each other's plans (`BACKUP-5`); each plan keeps and restores only
its own snapshots, whoever runs it. restic may take the lock of the other computer for stale and prune during its
backup.

Fix: an id of the computer of its own (e.g. the machine id) with the plan; a warning when plans of two computers
share a name.

### `BK-14` What a run skipped is kept and shown without a limit (read)

- restore: `Command::output()` keeps all of standard error, every "ignoring error for" line goes into a list, over
  ipc and into one DOM node each; a read-only target of a million files is hundreds of megabytes, and the spread of
  `replaceChildren(...)` fails above some ten thousand rows
- backup: the thread of standard error keeps every line, joins them again, and the page appends a row per skipped
  path
- with `--json` restic may write errors as json lines, which match neither "skipping" nor "error:", so some skipped
  paths may never show (suspected)

Fix: read standard error line by line; keep the first 100 lines, a count of the rest, the `Fatal:` line and the last
one; the page shows 100 and "and n more".

## Could fix

### `BK-19` Staged files a crash leaves are never removed (read)

On the volume only: a file in `<id>/staging/` left between the write and the move stays and takes its room, though the
size of the repository leaves it out; the storage cleans only its own `.tmp`. An upload is also staged twice (by
`LocalResticFileStorage` and by `FilesystemLocalStorage`), with three directory syncs.

Fix: the store writes the target through the storage and fails the write on a wrong hash; stale staged files go at
the start.

### `BK-20` An upload restic repeats is refused (read; that restic fails then is suspected)

restic sends a file again when the answer was lost and gets 403 for the file that is there.

Fix: for a file named by its hash, 200 when the one there has the size.

### `BK-21` A backup of the deployment links every file of the repositories first (read)

`cp -al` of `backend/data` makes a hard link for each pack, then `rm -rf` removes them: no loss, but slow and many
inodes.

Fix: leave `restic` out when linking.

### `BK-22` Small things of sakbackup (read)

- an empty entry of `PATH` is the current directory: `./restic` would get the token and the password; skip entries
  that are not absolute
- the other `RESTIC_*` variables of the user and the proxies reach restic (`RESTIC_CACHE_DIR`, `RESTIC_CACERT`, …):
  an environment of known variables only
- a backup that skipped paths (exit 3 of restic) ends the command line with 0: a code of its own
- the last line of standard error is the error shown: the `Fatal:` line, with the token redacted whatever restic does
- the check at the start shows the version of restic but asks for no minimum, though `--files-from-verbatim` and exit
  code 3 need a recent one; restic 0.16 can not overwrite read-only files in a restore in place, 0.17 with
  `--overwrite if-changed` leaves unchanged files alone
- `restic snapshots --json` is read as a whole, also to look up one id, and rendered in one go
- progress lines of restic may come 60 times a second (suspected): `RESTIC_PROGRESS_FPS`
- the answers of the api are read without a limit of size

## Tests that are missing

By risk; each names what to assert and where.

1. two `POST config` at once leave one config, the second one is refused (backend)
2. a backup of a second computer during a prune ends with `restic check` fine; a refused lock shows as the expiry
   that failed, the snapshot stays reported (sakbackup e2e)
3. the command line when it fails: an ambiguous snapshot, no restic (restic is also looked for in `/usr/bin`, so a
   test needs a machine without it) (sakbackup e2e)
4. the window of sakbackup (`SakbackupWindowTest`) covers the check of restic, a running backup, the look of a running
   plan and the excludes of a plan; not the password dialogs of a run, a restore and the snapshots, the messages of a
   failure, the skipped files, the rest of the form of a plan, the confirmations
