---
title: "Review: memory of the backend"
linkTitle: "Memory"
description: "The review of the memory the backend uses: the JVM settings, the thumbnails, the recordings, the text discovery and the S3 listing."
weight: 3
---

# Review: memory of the backend

**Created:** 2026-11-02

**Reviewed again:** 2026-11-12: the open findings are still open, `MR-13` is new, the numbers are those of today;
the fixed findings are in the history of git.

Scope: the memory of `sak-backend` and of the modules it runs (`sak-s3`, `sak-openrouter`, `sak-auth`, `sak-api`) on
the server of a SAK instance (Hetzner `cx23`: 2 vCPU, 4 GB, shared with caddy, victorialogs and vector).

What is good: uploads go to a temporary file through a 64 KiB buffer, files and thumbnails are sent from the disk
(`LocalPathContent`), S3 objects are streamed in both directions, the image size is read from the header without
decoding (`ImageMetadata`), and the maps in memory (`ApiSignature.seen`, `TokenService.usedLoginTokens`,
`FileService.thumbnailLocks`) remove their old entries.

## Numbers used below

- An image has at most 2048 px on its longer side (`MAX_IMAGE_SIZE`). Decoded, it has 12.6 MB (jpeg, 3 bytes for each
  pixel), 16.8 MB (png with alpha, 4 bytes) or 33.5 MB (png with 16 bits for each of 4 channels, `MR-13`). The steps of
  `scaled()` add approximately 5.6 MB. At most one decode for each processor runs at a time, 2 on a `cx23`.
- A file has at most 10 MiB (`DEFAULT_MAX_SIZE`). A recording of the apps is 16 kHz mono 16 bit (`RECORDING_RATE`):
  300 s are 9.6 MB. The backend takes only pcm of 8 or 16 bit, mono or stereo (`FileService.isRecording`, `SEC-1`),
  and reads the waveform in blocks of at most 64 KiB.
- The check of the admin password is an argon2id of 64 MiB on the heap, one at a time (`AdminPassword.check`, `SEC-2`).
- The backend runs with the serial gc and a heap of at most 192 MB (`-Xmx192m`), at most 64 MB of direct memory, and
  ends at an `OutOfMemoryError` (`-XX:+ExitOnOutOfMemoryError`), which systemd starts again after 5 s; the container
  has at most 640 MB (`sak-backend/build.gradle.kts`, `sak-backend.container.j2`).

## Measurement

`MR-1` to `MR-4` and `MR-7` to `MR-10`, which the table names, are fixed and no longer listed. The table is from
2026-11-02 and was not measured again: the todo app, the connect of an app with a QR code and the password check of
`SEC-2` came later, and the load had no password login.

The backend ran locally like on a `cx23` (`taskset` on 2 cores, `-XX:ActiveProcessorCount=2 -XX:MaxRAM=4g`), with a
fake of OpenRouter. The load: 500 api requests, 80 uploads of 2048 × 1536 px jpegs and 6 recordings of 5 minutes
(16 kHz mono), the thumbnails of the 80 photos with 64 requests at the same time, the 6 waveforms, the 6
transcriptions and 6 text recognitions at the same time.

| | Before | After MR-1 to MR-3 | With MR-4 | With MR-8 to MR-10 |
|---|---|---|---|---|
| RSS idle after the start | 319 MB | 329 MB | 305 MB | 261 MB |
| Largest RSS | 1414 MB | 610 MB | 428 MB | 377 MB |
| RSS 15 s after the load | 1413 MB | 575 MB | 423 MB | 310 MB |
| RSS after a full GC | 649 MB | 388 MB | 423 MB | 273 MB |
| Heap committed at the end | 1024 MB | 309 MB | 176 MB | 104 MB |
| Metaspace / compiled code | 78 / 28 MB | 78 / 27 MB | 72 / 27 MB | 61 / 16 MB |

With every heap from 96 MB to 256 MB (G1 and serial) the load ran without an `OutOfMemoryError`; at 96 MB the
thumbnails took 2.9 s instead of 1.7 s. The JVM, without the heap, takes approximately 200 MB (metaspace 73 MB).

## Must fix

### `MR-13` Two thumbnails of a png of 16 bits a channel end the backend

Any signed in user. A png of 2048 × 2048 px with 16 bits for each of red, green, blue and alpha passes the checks of an
upload (`ImageMetadata` reads its size, not its depth) and is small when its colors are flat: 158 KB. `ImageIO` decodes
it to a raster of `short`s, 33.5 MB, twice what the numbers of this review counted for a png. A thumbnail of 480 px or
1024 px is decoded without subsampling, and two decodes run at the same time (`decodes`, one for each processor): two
requests for thumbnails of it (two sizes, or two such files) take 67 MB plus the steps of `scaled()`. A check of the
admin password at the same time takes 64 MiB more; it waits only for another password check.

Shown with a small program under the JVM options of the backend (serial gc, `-Xmx192m`, the decode and `scaled()` of
`FileService`): one decode passes with 100 MB of other live data, two at a time pass with 40 MB and end the JVM with
60 MB; `build.gradle.kts` counts about 100 MB of live heap at the peak. Not shown with the backend itself. The marker
of no thumbnail is not written, so every view of the note asks again.

Fix: refuse at the upload what the apps never send (a png of more than 8 bits a channel; `ImageMetadata` reads the
depth from the same header); and one budget of memory for the decodes and the password check instead of one decode for
each processor: a semaphore of bytes, taken by the size of the raster before a decode (`reader.getRawImageType`, width
× height × bytes of a pixel) and by the 64 MiB of a password check.

## Could fix

### `MR-11` `kotlin-reflect` is loaded with the first response

Ktor gets the type of a response with `typeOf`; with `kotlin-reflect` on the classpath this loads 1080 classes
(approximately 13 MB of metaspace). `ktor-server-core` and `jackson-module-kotlin` (`sak-s3`) depend on it.

Fix: try the backend without `kotlin-reflect` (an `exclude` of the dependency), with all tests and the e2e tests.

### `MR-12` Two generations of Jackson are loaded

Jackson 3 (`sak-s3`, the XML of the S3 api) and Jackson 2 (`java-jwt` of `ktor-server-auth-jwt`) are loaded both,
approximately 1000 classes.

Fix: write the few XML messages of the S3 api with kotlinx or by hand, and drop Jackson 3 and
`jackson-module-kotlin`.

### `MR-5` An S3 listing walks its whole directory for each page

`BinaryStorageS3Backend.listObjects` gets the full list below the directory of the prefix (the bucket
without a prefix) from `FilesystemBinaryStorage.list` (a
`BlobListEntry` with its segments for each object), sorts it, and then takes at most `maxKeys` (1000). A bucket with
100,000 objects makes approximately 30 MB of objects for each page, and listing all pages walks the bucket 100 times.
A static site seldom has this many objects, so the risk is small.

Fix: walk the directories lazily in the order of the keys and stop after `maxKeys`; start at `startAfter`. Sort the
names of a directory as `name + "/"`, else `a/b` comes before `a-b` (`-` is before `/`), which is not the order of S3.

### `MR-6` Some lists are read whole from the database and then paged

The grocery lists, items, suggestions and categories, the lists and tasks of todo, the users, the labels of the
notes, the redirects and the buckets are read as a whole and paged in memory (`page.of(all)`). Only the notes and the
labels (`/api/v1/labels`) are paged in the database. For the data of one household this is small; it grows with the
data, not with the page.

Fix: when a list can grow without a limit (the items of a grocery list, the tasks of a todo list), page it in its
repository like `NoteRepository`.

## Elsewhere

Found with the security review and listed there, as they are about what an attacker can do: the memory of a key of
each client in every `RateLimiter` (`SEC-5`), the answer of victorialogs for the access logs read whole (`SEC-10`),
the answer of OpenRouter read whole (`SEC-20`), the change streams without a largest number for a user (`SEC-29`).
