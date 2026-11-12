---
title: "Review: sak-storage"
linkTitle: "Storage"
description: "The review of sak-storage, the binary data on the data volume: edge cases, concurrency and the tests."
weight: 5
---

# Review: sak-storage

**Created:** 2026-11-02

**Checked again:** 2026-11-12, every finding listed is still open; the fixed ones are in the history of git.

Scope: `sak-storage` (`BinaryStorage`, `FilesystemBinaryStorage`, `BlobPath`, `BlobContent`, the model), which keeps
all binary data of the backend (`MODEL-7`): the files of the file storage, their thumbnails and the objects of the
buckets. Each finding with "Reproduced" was shown with a test against a real directory.

What is good: a write is staged and moved into place at once, the staged file and its directory are synced, a reader
never sees half a blob, a path can not leave the root (`BlobPath` refuses `.`, `..`, `/` and NUL), and a blob that
goes while it is read is "none", not an error, in `open`, `stat` and `usage`.

## Could fix

### `SR-7` The root shows its staging directory

Reproduced: `list` and `usage` of the empty path (the root) count the staged files in `.tmp` as blobs, with paths no
`BlobPath` takes; `deleteTree` of the root deletes the root itself and the writes being staged. No caller uses the
root today; `FileService` and the S3 api always name a directory below it.

Fix: skip `STAGING_DIRECTORY` at the root in `list`, `usage` and `deleteTree`, or refuse the empty path there.

### `SR-8` A segment longer than the file system takes fails late

Reproduced: a segment of 300 characters fails at the move with a `FileSystemException` ("File name too long"); the
S3 api answers 500 to such a key instead of an error of the request.

Fix: `BlobPath` refuses a segment longer than 255 bytes in UTF-8; the S3 api answers `KeyTooLongError`.

### `SR-9` A move does not sync the directory it leaves

`move` syncs the directory of the target, not the one of the source; after a crash the blob can show at both paths.
For `FileService` the source is an upload, which is deleted anyway.

Fix: sync the parent of the source too.

### `SR-10` The interface does not say all it does

`BinaryStorage` documents `openRange`, `move`, `usage`, `content` and `directory`. Not written down: `write` of an
`InputStream` closes the stream and replaces a blob at the path; `open` and `stat` answer null for a missing blob;
`delete` is idempotent and removes the directories it leaves empty; `list` is recursive, its paths relative to the
prefix and sorted; `BlobPath` has no comment (AGENT.md asks for them on public APIs).

Fix: a comment for each.
