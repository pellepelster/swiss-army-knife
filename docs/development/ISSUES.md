---
title: "Issues"
description: "The open issues of SAK: what is to do and not yet done, moved here from the issues of GitHub."
weight: 16
---

# Issues

The open issues of SAK. They were the open issues of the repository on GitHub and were moved here on 2026-11-22;
the closed issues stay on GitHub. Each issue keeps its number of GitHub as its id, a finding of a review also the id
of the finding. An issue that is done is deleted here.

The texts are as they were written: a path or a name in them is the one of the date of the issue, e.g. `sakctl` is
`sakadmin` now. They were not checked again when they were moved.

A user can still open an issue on GitHub, e.g. a question on the privacy policy of the Android apps. Such an issue is
answered there; when it is work to do, it is moved here and closed there.

A weakness that is kept on purpose is in [ACCEPTED-RISKS.md](ACCEPTED-RISKS.md), the open findings of the reviews are
in [the reviews](reviews/).

## `#130` Coordinate the automatic reboot of unattended upgrades with sak-backend reboot

**Created:** 2026-10-30. **Labels:** enhancement.

Unattended upgrades reboot the server on their own at 04:00 when a reboot is pending
(`common_unattended_upgrades_automatic_reboot`, `roles/common/templates/52unattended-upgrades-sak.j2`). That reboot
does not know the deployment: it can interrupt a running backup or restore, and it stops the containers in whatever
order systemd chooses.

`sak-backend reboot` is a coordinated reboot: it takes the lock of backup and restore, waits for apt, stops the units
in order (ingress, backend, vector, victorialogs) and then reboots.

**Recommended change:**

- Turn off `Unattended-Upgrade::Automatic-Reboot`.
- A systemd timer of the backend role runs `sak-backend reboot --if-pending` at the time of
  `common_unattended_upgrades_automatic_reboot_time` (04:00), so every automatic reboot is coordinated.
- A reboot that is refused because a backup runs is tried again at the next run of the timer.

## `#132` Grow the data volume in place instead of refusing a recreate

**Created:** 2026-10-30. **Labels:** none.

Growing the data volume of a deployment is planned as a recreate, and the plan refuses it, because the data volume is
never created anew. Latent today: it bites the day a release raises the size of the data volume.

#### What happens

- The volume provisioner (`sak-cli/cloud/src/hetzner/volume/provisioner.rs`) compares the sizes: a declared size
  larger than the live one becomes `DiffItem::recreate("size", …)`.
- The data volume is never recreatable (the spec in `sak-cli/cloud/src/hetzner/deployment/spec.rs` marks only the
  server and the ssh key recreatable), so the plan fails with `RecreateRefused`: "volume … would have to be created
  anew".
- The spec declares 16 GB (`data_volume_gb: 16` in `spec.rs`).

**Scenario:** a release raises `data_volume_gb` from 16 to 32 → every deploy of an existing deployment is refused,
although nothing needs to be destroyed.

#### What it should do

Hetzner grows a volume in place: `POST /volumes/{id}/actions/resize` with the new size (a volume can only grow, never
shrink). So:

1. The size difference becomes a change (`DiffItem::changed`), not a recreate, when the declared size is larger; a
   smaller declared size stays as it is today (a volume grown beyond the declared size is up to date).
2. `sak-cli/cloud/src/hetzner/api/volumes.rs` gets a `resize` action, and the volume's update (`HetznerKind::update`)
   calls it and waits for the action.
3. The filesystem on the volume is grown too, after the resize: the provisioning (the storage role of the playbook)
   runs `resize2fs` on the mounted device, so the new space is usable.

From the review `docs/development/reviews/SAKADMIN-17.md` (CP-6).

## `#133` Back up and restore a deployment from the sakctl ui

**Created:** 2026-11-01. **Labels:** enhancement, component: sakctl.

As a user of the sakctl ui, I want to back up a deployment and restore a backup into it from the ui, so I do not need
the sakctl cli for my data.

Today only the sakctl cli does it (`sakctl backup <name>`, `sakctl restore <name> <file>`, refer to FDR-007
(`docs/development/fdr/FDR-007-backup.md`), which notes "The sakctl ui has no backup or restore yet."). `SAKADMIN-12` in
UI-UX.md (`docs/development/ui-ux/UI-UX.md`) asks for feature parity between the sakctl ui and the sakctl cli.

#### What it should do

- **Backup.** The page of a deployment offers a backup. It runs the backup of `core` the sakctl cli runs, shows each
  step of the server while it runs, and at the end the path and the size of the file in `~/.sak/<name>/backups/`, with
  a way to open its folder.
- **Restore.** The page of a deployment offers a restore of a backup file, chosen from `~/.sak/<name>/backups/` or
  anywhere else. It says what goes (all data of the deployment is replaced, the apps are unavailable meanwhile) and
  asks for a confirmation before it starts (`UX-4.1`, `SAKADMIN-10`), then shows each step of the server.
- **The same rules as the sakctl cli** (FDR-007): only complete backups, a restore that cannot succeed changes
  nothing, one backup or restore at a time. A refusal shows the same text as the sakctl cli.
- **Texts** in `i18n/texts.yml` (`ctl.ui`, or `ctl.common` for what both show).

#### Done when

- Backup and restore work from the page of a deployment in the sakctl ui, in english and german.
- The tests of the sakctl ui cover both, and `docs/development/screenshots/` has a screenshot of each.
- FDR-007 no longer says the sakctl ui has no backup or restore.

## `#134` Encrypt the files of the backend at rest

**Created:** 2026-11-02. **Labels:** enhancement, area: backend, security.

#### Problem

The binary data of the backend is plain on the data volume:

- the files of the file storage (photos and recordings of the notes and the grocery lists),
- their thumbnails and waveforms,
- the objects of the S3 buckets (static hosting).

`AGENT.md` asks for all data to be encrypted at rest; data that is not is a defect. The database has its own plan
(FDR-002, listed in `docs/development/ACCEPTED-RISKS.md`), the files have none.

#### Proposal

All binary data goes through `FilesystemBinaryStorage` in `sak-storage` (`MODEL-7`), so the encryption can be in one
place: an encrypting `BinaryStorage` that wraps the file system one.

To decide:

- the key: derived from the password of the deployment like the other keys (`SECURITY-1`), or a key the backend makes
  and keeps, and how it reaches the backend on a restart;
- the cipher: a streaming AEAD in chunks (e.g. AES-GCM or ChaCha20-Poly1305 per 64 KiB), so a large blob is never held
  in memory and `openRange` of the S3 api reads only the chunks of the range;
- the sizes: `stat`, `list` and `usage` answer the size of the plain content, not of the encrypted file;
- the migration of the existing blobs, and the backups (`SAKCTL-8`), which are encrypted already as a whole.

Until then, the plain files belong in `docs/development/ACCEPTED-RISKS.md`.

## `#145` Verify the image signatures on the server with podman once it reads sigstore bundles

**Created:** 2026-11-04. **Labels:** none.

The images of SAK (sak-backend, sak-ingress, sak-cli-tools, sak-cli-docker-canary) are signed with cosign 3, keyless,
by the workflow at its ref (`mise run image:sign`). cosign 3 stores the signature as a sigstore bundle
(`application/vnd.dev.sigstore.bundle.v0.3+json`, an in-toto statement whose subject is the digest) in an OCI index
tagged `sha256-<hex>`; ghcr.io has no referrers api.

Today sakctl verifies the signatures (`sak-cli/src-tauri/src/core/images.rs`) and hands the server the images by their
digests (`backend_image`, `backend_ingress_image` in the inventory), so the server pulls exactly the verified image.
The server itself checks nothing: podman's `sigstoreSigned` policy (`/etc/containers/policy.json`, `registries.d` with
`use-sigstore-attachments`) reads only the older cosign format (`.sig` tags, simple signing payloads), not the bundles
of cosign 3.

As soon as podman (containers/image) verifies sigstore bundles:

- add a `sigstoreSigned` policy for `ghcr.io/pellepelster` to the server (role common or backend): keyless, the Fulcio
  root and the Rekor key of sigstore, the identity
  `https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml@refs/tags/v<version>`
  (`@refs/heads/main` for `latest`) and the issuer `https://token.actions.githubusercontent.com`
- decide whether sakctl keeps its own check (it also covers the tools image on the machine of the user) or only the
  server checks the server images
- update `SYSTEM.md` (`SERVER-1`) and `docs/development/ACCEPTED-RISKS.md`

## `#150` (`MR-13`) Two thumbnails of a png of 16 bits a channel end the backend

**Created:** 2026-11-13. **Labels:** bug, area: backend, review, severity: medium, security.

Context: the backend runs with the serial gc and a heap of at most 192 MB (`-Xmx192m`, `sak-backend/build.gradle.kts`)
and ends at an `OutOfMemoryError` (`-XX:+ExitOnOutOfMemoryError`), systemd starts it again after 5 s; the container
has at most 640 MB. Measured on 2026-11-02 under load like on a Hetzner `cx23` (2 vCPU, 4 GB): about 100 MB of live
heap at the peak, 261 MB RSS idle, 377 MB at the peak.

Any signed in user. A png of 2048 × 2048 px with 16 bits for each of red, green, blue and alpha passes the checks of
an upload (`ImageMetadata` reads its size, not its depth) and is small when its colors are flat: 158 KB. `ImageIO`
decodes it to a raster of `short`s, 33.5 MB, twice a png with 8 bits a channel (16.8 MB). A thumbnail of 480 px or
1024 px is decoded without subsampling, and two decodes run at the same time (`decodes`, one for each processor): two
requests for thumbnails of it (two sizes, or two such files) take 67 MB plus the steps of `scaled()`. A check of the
admin password at the same time takes 64 MiB more; it waits only for another password check.

Shown with a small program under the JVM options of the backend (serial gc, `-Xmx192m`, the decode and `scaled()` of
`FileService`): one decode passes with 100 MB of other live data, two at a time pass with 40 MB and end the JVM with
60 MB; `build.gradle.kts` counts about 100 MB of live heap at the peak. Not shown with the backend itself. The backend
ends before it writes the marker of no thumbnail, so every view of the note asks again.

Fix: refuse at the upload what the apps never send (a png of more than 8 bits a channel; `ImageMetadata` reads the
depth from the same header); and one budget of memory for the decodes and the password check instead of one decode for
each processor: a semaphore of bytes, taken by the size of the raster before a decode (`reader.getRawImageType`, width
× height × bytes of a pixel) and by the 64 MiB of a password check.

From the review of the memory of the backend (`docs/development/reviews/memory-review.md`), reviewed again at commit
db41d795; the review is removed, its findings are these issues.

## `#151` (`MR-11`) `kotlin-reflect` is loaded with the first response

**Created:** 2026-11-13. **Labels:** enhancement, area: backend, review, severity: low.

Ktor gets the type of a response with `typeOf`; with `kotlin-reflect` on the classpath this loads 1080 classes
(approximately 13 MB of metaspace). `ktor-server-core` and `jackson-module-kotlin` (`sak-s3`) depend on it.

Fix: try the backend without `kotlin-reflect` (an `exclude` of the dependency), with all tests and the e2e tests.

From the review of the memory of the backend (`docs/development/reviews/memory-review.md`), reviewed again at commit
db41d795; the review is removed, its findings are these issues.

## `#152` (`MR-12`) Two generations of Jackson are loaded

**Created:** 2026-11-13. **Labels:** enhancement, area: backend, review, severity: low.

Jackson 3 (`sak-s3`, the XML of the S3 api) and Jackson 2 (`java-jwt` of `ktor-server-auth-jwt`) are loaded both,
approximately 1000 classes.

Fix: write the few XML messages of the S3 api with kotlinx or by hand, and drop Jackson 3 and `jackson-module-kotlin`.

From the review of the memory of the backend (`docs/development/reviews/memory-review.md`), reviewed again at commit
db41d795; the review is removed, its findings are these issues.

## `#153` (`MR-5`) An S3 listing walks its whole directory for each page

**Created:** 2026-11-13. **Labels:** enhancement, area: backend, review, severity: low.

`BinaryStorageS3Backend.listObjects` gets the full list below the directory of the prefix (the bucket without a
prefix) from `FilesystemBinaryStorage.list` (a `BlobListEntry` with its segments for each object), sorts it, and then
takes at most `maxKeys` (1000). A bucket with 100,000 objects makes approximately 30 MB of objects for each page, and
listing all pages walks the bucket 100 times. A static site seldom has this many objects, so the risk is small.

Fix: walk the directories lazily in the order of the keys and stop after `maxKeys`; start at `startAfter`. Sort the
names of a directory as `name + "/"`, else `a/b` comes before `a-b` (`-` is before `/`), which is not the order of S3.

From the review of the memory of the backend (`docs/development/reviews/memory-review.md`), reviewed again at commit
db41d795; the review is removed, its findings are these issues.

## `#155` (`SEC-22`) What is used once is forgotten with a restart

**Created:** 2026-11-13. **Labels:** enhancement, area: backend, review, severity: low, security.

The used login tokens and connect tokens, the nonces of the signed requests and every limit are in memory. After a
restart:

- a used login link or QR code works once more within its time,
- the page of a code can not say it was used (404),
- a relayed signed request can be sent a second time within its two minutes,
- the limits start again.

A deploy restarts the backend; so does a crash, e.g. of an `OutOfMemoryError`. The code says it for the links;
`ACCEPTED-RISKS` says a code is good once.

Fix: the used ids in a small table with their end; or an entry in `ACCEPTED-RISKS`.

From the security review (`docs/development/reviews/security-review.md`, `SEC-22`), at commit f66cdf35.
