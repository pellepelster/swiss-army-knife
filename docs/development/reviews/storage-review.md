---
title: "Review: sak-storage"
linkTitle: "Storage"
description: "The review of sak-storage, the binary data on the data volume: edge cases, concurrency and the tests."
weight: 5
---

# Review: sak-storage

**Created:** 2026-11-02

Scope: `sak-storage` (`BinaryStorage`, `FilesystemBinaryStorage`, `BlobPath`, `BlobContent`, the model), which keeps
all binary data of the backend (`MODEL-7`): the files of the file storage, their thumbnails and the objects of the
buckets. Each finding with "Reproduced" was shown with a test against a real directory.

What is good: a write is staged and moved into place at once, the staged file and its directory are synced, a reader
never sees half a blob, a path can not leave the root (`BlobPath` refuses `.`, `..`, `/` and NUL), and a blob that
goes while it is read is "none", not an error, in `open`, `stat` and `usage`.

## Must fix

### `SR-1` Deletes in one directory at the same time fail

Reproduced: 8 threads that delete the 8 blobs of one directory at the same time; 40 of 2400 deletes threw, although
their blob was gone. `pruneEmptyParents` checks that the directory exists and then lists it; when another delete
removes the directory in between, `Files.list` throws `NoSuchFileException`.

Where it hurts: `FileService.store` deletes its upload blob (`uploads/<uuid>`) in a `finally`; two uploads that end at
the same time can make the delete throw after the file is stored, and the upload answers 500. The S3 api fails a
delete of an object the same way when a client deletes objects in parallel (e.g. `aws s3 sync --delete`).

Fix: in `pruneEmptyParents`, a `NoSuchFileException` of `Files.list` or of the delete means the directory went; stop
there. A test with concurrent deletes in one directory.

### `SR-2` A listing fails while blobs are deleted

Reproduced: `list` while another thread deletes the blobs below the prefix; 39 listings failed. `Files.walk` throws
an `UncheckedIOException` (`NoSuchFileException`) for a directory that goes while it is walked. `deleteTree` walks the
same way. The S3 api answers 500 to a ListObjects while a client deletes objects.

Fix: walk with `Files.walkFileTree` and a `visitFileFailed` that skips a `NoSuchFileException`, as `usage` does.

## Should fix

### `SR-3` The size of a content can differ from what it reads

Reproduced: `content(path)` of a blob of 3 bytes, the blob replaced by one of 6 bytes, then `open()` reads 6 bytes
while `size` says 3. `stat` and `open` are two steps. A response sends `size` as its `Content-Length`
(`BlobResponse`), so it breaks off or carries the wrong bytes. The files of the file storage never change under their
path, but the objects of a bucket do on every put, and the S3 api also reads with `stat` and then `openRange`.

Fix: open first and take the size from the open file (`FileChannel.size()`), so size and content are of the same
file; `BlobContent` keeps that or opens and checks again.

### `SR-4` A failed write leaves its parent directory, which blocks a blob of that name

Reproduced: a write of `a/b` that fails while it writes leaves the empty directory `a`; a later write of the blob `a`
fails with `BlobPathConflictException`, although there is no blob. `write` creates the parent before it stages the
content and does not remove it on a failure.

Fix: create the parent only after the content is staged, right before the move, or prune the parent when the write
fails.

### `SR-5` What a crash leaves staged is never removed

Reproduced: a file in `.tmp` stays after a new `FilesystemBinaryStorage` on the same root. A backend that stops while
it writes (an upload, a put of S3, a restart during a deploy) leaves the staged file for good; the disk fills slowly
and nothing counts it.

Fix: on construction, delete the staged files older than some minutes (a write that runs is younger).

### `SR-6` The tests of the module are few

`FilesystemBinaryStorageTest` has 4 tests (write, move, a conflict of move, content). The S3 tests cover some more
through `BinaryStorageS3Backend` (overwrite, nested keys, conflicts of keys, traversal, listing). Not tested
anywhere: the conflict of a write with a blob as parent or a directory as target; `openRange` with an offset past the
end and lengths beyond it; `delete` of a missing blob and the pruning of empty directories; `deleteTree`; `list` of a
missing prefix, nested blobs and the order; `usage`; a failed write that leaves nothing staged (`SR-4`); every rule
of `BlobPath` (the reserved first segment, `.`, `..`, `/`, NUL, the empty path); `write` of an `InputStream` closes
the stream; the concurrent cases (`SR-1`, `SR-2`).

Fix: tests for each, the concurrent ones as in `SR-1` and `SR-2`.

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

### `SR-10` The interface does not say what it does

`BinaryStorage` documents `move` and `usage` only. Not written down: `write` of an `InputStream` closes the stream
and replaces a blob at the path; `open`, `openRange` and `stat` answer null for a missing blob; `openRange` clamps the
range to the blob; `delete` is idempotent and removes the directories it leaves empty; `list` is recursive, its paths
relative to the prefix and sorted; `BlobPath` and `BlobContent` have no comments (AGENT.md asks for them on public
APIs).

Fix: a comment for each.
