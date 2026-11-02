---
title: "Review: memory of the backend"
linkTitle: "Memory"
description: "The review of the memory the backend uses: the JVM settings, the thumbnails, the recordings, the text discovery and the S3 listing."
weight: 3
---

# Review: memory of the backend

**Created:** 2026-11-02

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

### ~~`MR-1` Many thumbnails at the same time can use all of the heap~~

**Fixed:** a `Semaphore` lets one decode for each processor run; `FileService.decode` reads with
`setSourceSubsampling` at least four times the size (a photo of 2048 px for 128 px is read with 512 px).

`FileService.thumbnail` decodes the full image with `ImageIO.read` and then scales it. One lock is there for each
content and size only, so different photos decode at the same time, up to the 64 threads of `Dispatchers.IO`. A list
of notes or a grocery list with many new photos asks for their thumbnails at the same time (and for up to four sizes
each, `SCALED_SIZES`). Each decode keeps approximately 18 to 22 MB; 50 decodes at the same time need more than the
heap of 1 GB, and the backend fails with `OutOfMemoryError`.

Fix:

1. Limit the decodes to the number of processors with a `Semaphore` around `decodeThumbnail` (the waveforms too).
2. Decode a smaller image: read with an `ImageReader` and `ImageReadParam.setSourceSubsampling(n, n, 0, 0)`, with `n`
   the largest power of 2 that keeps the image at least twice the asked size. For 128 px of a 2048 px photo this
   decodes 256 × 256 px (0.3 MB) instead of 2048 × 2048 px (16.8 MB). `scaled()` then makes one or two steps only.

### ~~`MR-2` The text discovery of a recording keeps the recording four times in memory~~

**Fixed:** `TextRecognizer` and `SpeechTranscriber` take the `Path` of the file; `base64Body` encodes the file block by
block while the request is sent, with its length known.

`FileTextService.discover` reads the file with `Files.readAllBytes`. `OpenRouterSpeechTranscriber` encodes it to a
Base64 string (4/3 of the size), puts it into a JSON tree, makes a string of the tree (`toString()`) and
`BodyPublishers.ofString` makes bytes of that string. For a recording of the apps (9.6 MB) this is approximately 50 MB
at the same time; for a 48 kHz stereo WAV (57.6 MB) approximately 320 MB. The answer of OpenRouter is small. Images
(at most 2048 px) are smaller, but go the same way in `OpenRouterTextRecognizer`.

Fix: stream the body. Write the JSON before and after the data as text and the data through
`Base64.getEncoder().wrap(out)` from the file (`BodyPublishers.ofInputStream` with a `PipedInputStream`, or a small
`BodyPublisher`). Then the memory is a buffer, independent of the size of the file. The interfaces
`TextRecognizer`/`SpeechTranscriber` take a `Path` (or an `InputStream` with its length) instead of a `ByteArray`.

## Should fix

### ~~`MR-3` The waveform of a recording reads it twice into memory~~

**Fixed:** `FileService.peaks` reads the recording in blocks of 16384 frames and keeps the 48 peaks only.

`FileService.waveform` reads the full recording (`readAllBytes`) and makes a `FloatArray` of all samples: 9.6 MB +
19.2 MB for a recording of the apps, 57.6 MB + 57.6 MB for a 48 kHz stereo WAV. It needs only 48 peaks.

Fix: the header tells the number of frames (`frameLength`). Read the stream in blocks of 64 KiB and keep the largest
absolute sample of the current bar; the memory is the block and 48 floats.

### ~~`MR-4` The JVM has no memory settings~~

**Fixed:** the start script has `-XX:+UseSerialGC -XX:+UseCompactObjectHeaders -Xmx192m -XX:MaxDirectMemorySize=64m
-XX:+ExitOnOutOfMemoryError` (`applicationDefaultJvmArgs`), approximately two times the largest heap measured; the
container has `--memory=640m`.

The backend shares 4 GB with caddy, victorialogs and vector, but can take a heap of 1 GB, 1 GB of direct memory,
metaspace, the code cache and a stack for each thread. G1 gives a heap back to the system only after a full GC, so
after one peak (`MR-1`, `MR-2`) the memory stays taken. The container has no limit, so the kernel can stop caddy or
victorialogs instead of the backend.

Fix (in `applicationDefaultJvmArgs` of `sak-backend/build.gradle.kts`, measured first with
`-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory`):

- `-XX:+UseCompactObjectHeaders` (product in JDK 25): object headers of 8 instead of 12 bytes, typically 10 to 20 %
  less live heap.
- `-XX:+UseSerialGC`, or with G1 `-XX:G1PeriodicGCInterval=60000` so the heap shrinks again when the backend is idle.
  A backend for one household does not need the throughput of G1, and SerialGC has no remembered sets.
- `-Xmx256m` (or `-XX:MaxRAMPercentage` with a container limit) after `MR-1` to `MR-3` limit the peaks;
  `-XX:MaxDirectMemorySize=64m`; `-XX:+ExitOnOutOfMemoryError`, so systemd starts it again (`Restart=on-failure`)
  instead of a backend that runs on in a broken state.
- `MemoryMax=` (`[Service]`) or `PodmanArgs=--memory=` in `sak-backend.container.j2`, so the backend can not take the
  memory of the other services.

### ~~`MR-8` The heap stays at its peak~~

**Fixed:** `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`. The live heap is 26 MB at idle and 34 MB after the
load, but the serial gc kept 177 MB committed. Now a full gc gives the free heap back.

### ~~`MR-9` Liquibase stays loaded after the migration~~

**Fixed:** `migrateIsolated` (`sak-model`) runs Liquibase in a class loader of its own, on a thread of its own, and
flushes the caches of `java.beans` (`ClassInfo.CACHE` kept its classes through soft references). The backend does one
full gc after its start: 1199 classes (Liquibase, snakeyaml) are unloaded, 11 MB of metaspace.

### ~~`MR-10` The compiled code is large for one household~~

**Fixed:** `-XX:TieredStopAtLevel=1`, the client compiler only: 16 MB instead of 27 MB of code, the load took as long.

## Could fix

### `MR-11` `kotlin-reflect` is loaded with the first response

Ktor gets the type of a response with `typeOf`; with `kotlin-reflect` on the classpath this loads 1080 classes
(approximately 13 MB of metaspace). `ktor-server-core` and `jackson-module-kotlin` (`sak-s3`) depend on it.

Fix: try the backend without `kotlin-reflect` (an `exclude` of the dependency), with all tests and the e2e tests.

### `MR-12` Two generations of Jackson are loaded

Jackson 3 (`sak-s3`, the XML of the S3 api) and Jackson 2 (`java-jwt`) are loaded both, approximately 1000 classes.

Fix: write the few XML messages of the S3 api with kotlinx or by hand, and drop Jackson 3 and
`jackson-module-kotlin`.

### `MR-5` An S3 listing walks the whole bucket for each page

`BinaryStorageS3Backend.listObjects` gets the full list of the bucket from `FilesystemBinaryStorage.list` (a
`BlobListEntry` with its segments for each object), sorts it, and then takes at most `maxKeys` (1000). A bucket with
100,000 objects makes approximately 30 MB of objects for each page, and listing all pages walks the bucket 100 times.
A static site seldom has this many objects, so the risk is small.

Fix: walk the directories lazily in the order of the keys and stop after `maxKeys`; start at `startAfter`. Sort the
names of a directory as `name + "/"`, else `a/b` comes before `a-b` (`-` is before `/`), which is not the order of S3.

### `MR-6` Some lists are read whole from the database and then paged

The grocery lists, items, suggestions and categories, the users, the labels, the redirects and the buckets are read
as a whole and paged in memory (`PageRequest.of(all)`). Only the notes are paged in the database. For the data of one
household this is small; it grows with the data, not with the page.

Fix: when a list can grow without a limit (items of a grocery list, suggestions), page it in its repository like
`NoteRepository`.

### ~~`MR-7` The largest file is 64 MB, an image of the storage is much smaller~~

**Fixed:** `DEFAULT_MAX_SIZE` is 10 MiB: a recording of the apps of 5 minutes (9.6 MB) fits, the request body of the
apps and the limit of caddy follow it.

`DEFAULT_MAX_SIZE` (64 MB) is the limit of every upload, but an image has at most 2048 px and a recording of the apps
at most 9.6 MB. The limit lets a client upload a 48 kHz stereo recording, which makes `MR-2` and `MR-3` six times
larger.

Fix: check the format of a WAV too (at most 16 kHz mono 16 bit, as the apps record), or make the largest recording
`MAX_RECORDING_SECONDS × RECORDING_RATE × 2` bytes and the limit of the body follow it.
