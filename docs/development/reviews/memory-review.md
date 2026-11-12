---
title: "Review: memory of the backend"
linkTitle: "Memory"
description: "The review of the memory the backend uses: the JVM settings, the thumbnails, the recordings, the text discovery and the S3 listing."
weight: 3
---

# Review: memory of the backend

**Created:** 2026-11-02

**Checked again:** 2026-11-12, every finding listed is still open; the fixed ones are in the history of git.

Scope: the memory of `sak-backend` and of the modules it runs (`sak-s3`, `sak-openrouter`, `sak-auth`, `sak-api`) on
the server of a SAK instance (Hetzner `cx23`: 2 vCPU, 4 GB, shared with caddy, victorialogs and vector).

What is good: uploads go to a temporary file through a 64 KiB buffer, files and thumbnails are sent from the disk
(`LocalPathContent`), S3 objects are streamed in both directions, the image size is read from the header without
decoding (`ImageMetadata`), and the maps in memory (`ApiSignature.seen`, `TokenService.usedLoginTokens`,
`FileService.thumbnailLocks`) remove their old entries.

## Numbers used below

- An image has at most 2048 px on its longer side (`MAX_IMAGE_SIZE`). Decoded, it has 12.6 MB (jpeg, 3 bytes for each
  pixel) or 16.8 MB (png with alpha, 4 bytes). The steps of `scaled()` add approximately 5.6 MB.
- A recording of the apps is 16 kHz mono 16 bit (`RECORDING_RATE`): 300 s are 9.6 MB. The backend checks only the
  length (`MAX_RECORDING_SECONDS`), not the rate. A WAV that other clients upload can be 48 kHz stereo: 300 s are
  57.6 MB, below `DEFAULT_MAX_SIZE` (64 MB).
- The backend starts without JVM options. On 4 GB the JVM takes G1 and a largest heap of 1 GB (25 %), and the largest
  direct memory of Netty is also 1 GB.

## Measurement

`MR-1` to `MR-4` and `MR-7` to `MR-10`, which the table names, are fixed and no longer listed.

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

The grocery lists, items, suggestions and categories, the lists and tasks of todo, the users, the labels, the
redirects and the buckets are read as a whole and paged in memory (`page.of(all)`). Only the notes are paged in the
database. For the data of one household this is small; it grows with the data, not with the page.

Fix: when a list can grow without a limit (the items of a grocery list, the tasks of a todo list), page it in its
repository like `NoteRepository`.
