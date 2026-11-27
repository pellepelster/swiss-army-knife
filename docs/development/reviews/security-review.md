---
title: "Review: security"
linkTitle: "Security"
description: "The security review of the whole application: the login and the sessions, the routes and the data of the backend, the S3 api and the ingress, the web app, the Android apps, sakadmin and the server it builds, and the desktop apps."
weight: 8
---

# Review: security

**Created:** 2026-11-27 at commit 3d2e5a54; `SEC-47` to `SEC-50` and `SEC-52` to `SEC-54` fixed since. This review
replaces the review of 2026-11-12 and its checks. The findings of that review that are still open keep their ids (`SEC-16`, `SEC-26`,
`SEC-29`); `SEC-22` is issue `#155` of [ISSUES.md](../ISSUES.md). The new findings start at `SEC-47`.

Scope: the whole application. The login and the sessions (`sak-auth`, `sak-api`), the routes and the data of the backend
(`sak-backend`, `sak-model`, `sak-openrouter`, `sak-logs`, `sak-settings`, `sak-core`, `sak-config`), the static hosting
with the S3 api, the storage and the ingress (`sak-s3`, `sak-storage-local`, `sak-storage-s3`, `sak-ingress`,
`sak-dns`), the web app (`sak-frontend`), the Android apps (`android/`), sakadmin (`sak-admin-cli`), the server it
builds (`ansible/`), and the desktop apps (`sak-desktop-shared`, `sak-backup`). The question: what can somebody do who
should not, from the internet without a session, as a user of a multi user deployment, with one key of a bucket, with
a link or a QR code, as another program on the computer or the phone of a user, as root on the server against the
machine of the admin, or in one container of the server.

Not in scope: what [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts, unless the code is weaker than it says, and
the signing of the releases and the images, which is in [the review of the supply chain](supply-chain-review.md).

How it was done: the code was read, route by route and command by command, in five parts at the same time; each
finding below was then read again in the code. Nothing ran against a deployment. Where a finding rests on an estimate
or on what caddy, podman, victorialogs, the JDK or Android do, it says so.

Nothing found lets somebody without a session read data, get a session or get a role. Nothing found lets a user read,
change or delete the data of another user.

## What is good

- **Who sees what.** Every query of the notes, labels, files, grocery lists, todo lists, restic repositories and
  backup plans has the owner or `visible(user)` in its sql (`MODEL-6`), also the ids that come from a body; an id of
  another user is a 404 or a 422, the same as an id that does not exist. No body has a field for the owner or a role.
  No answer names the owner of a shared list.
- **Roles.** Every route below `/api/v1` but `/info` and the login is below `authenticate` and `requireRole`; the
  roles of a token are intersected with the roles of the address on every request, so a deleted or demoted user is
  refused at once. The hosting, the redirects, the users, the settings, the health and the access logs are for an admin
  only, `/deployment` for a signed request only.
- **Tokens.** ES256 with a key made on the server; the verifier pins the algorithm and checks the issuer, the use,
  `exp`, `nbf` and `iat` without leeway, so one kind of token is no other kind. `auth_time` goes through every
  connect, and a session ends 180 days after its sign-in. A sign out everywhere applies to sessions, links and codes,
  and gives the repositories of restic of the user new tokens.
  Used ids are taken with `putIfAbsent`; a wrong nonce or verifier does not use a token up.
- **The login.** A link request answers 202 before it looks at the address; `/exchange` and `/password` give one answer
  for every refusal; the password is counted before argon2 and compared in constant time; the host of the link comes
  from the configuration. The redirect after a sign-in refuses `//`, `\`, control characters and whitespace.
- **Limits and clients.** The backend listens on `127.0.0.1` only; caddy replaces an `X-Forwarded-For` from outside,
  and the backend takes the last entry. A client is an IPv4 address or an IPv6 /64.
- **Sql, LogsQL, regexes.** Only the jOOQ DSL; the search of the access logs is escaped and quoted. No regex of the
  backend is slow on the input it gets. No route starts a process.
- **Files.** The type of a file comes from its first bytes and is jpeg, png, gif, webp or wav: no html and no svg. The
  paths of the blobs come from a resource id or a UUID; the names of restic files are 64 hex characters. A jpeg has at
  most 100 scans; at most 8 thumbnails are made or waited for at once, more get a 503, so the decodes keep no thread
  from the other routes.
- **OpenRouter.** Off by default, and each user turns the AI on for themselves; `data_collection: deny`; the address of
  OpenRouter comes from the configuration only.
- **Buckets.** A key reaches its own bucket only, checked in every operation; the keys of a deleted bucket are refused.
  A key is split into segments, and `.`, `..` and NUL are refused. A body is written to its place only after its hash
  and its chunk signatures held. Presigned urls are refused. Jackson XML 3 reads no DTD and no outside entity (from its
  source). A hosted site is another origin than the web app, and caddy serves it read only from the disk.
- **caddy.** Its logs mask the addresses of clients and keep no query, no referer, no location of a redirect and
  no header that names a client address (`ingress:image:test` checks it). Domains and the targets of redirects are
  labels of `[a-z0-9-]` only, so nothing can be injected into its configuration; no `on_demand` TLS, a certificate
  only for a host an admin named; its admin api is on loopback and keeps no configuration.
- **The web app.** No `innerHTML`, no `eval`, no `javascript:` url, no `postMessage`; the only
  `bypassSecurityTrustUrl` is the fixed intent link of Android. The token goes only to `/api/v1/` of its own host; the
  service worker caches no answer of the api. The content security policy allows scripts of the page only, with
  `frame-ancestors 'none'`, `nosniff` and `no-referrer`.
- **The Android apps.** The session and the pending nonce are encrypted with a key of the keystore (AES-GCM); no
  backup, no cleartext, no WebView, only the main activity exported. A QR code is `https://host/connect#token=…` only,
  its host shown in ASCII, and the app asks before it signs in, naming the SAK instance and the user of the code. An
  update is checked by its sha256 and by Android.
- **sakadmin.** The host key is checked from the first connection on; the private ssh key goes to the agent through
  stdin; every value of the inventory is JSON quoted; the files of a run are in a random directory only the user reads.
  Every line of its command line goes through `printable_lines` of `sak-desktop-shared` (`log` of `cli/log.rs`), so
  no server puts an escape sequence into the terminal of the admin; only `sakadmin project ssh` is the terminal of
  the server. The window has no plugin command and loads its own pages
  only. No TLS check is turned off anywhere.
- **The desktop apps.** The sign-in listens on `127.0.0.1` only, with a state of 128 bits and pkce S256; on linux it
  refuses a connection of another user, off linux its start page has a secret. The token is in `Zeroizing`, is sent
  only to the api it was made for, and is in no environment of restic. The arguments of restic are numbers, hex ids or
  files; its password is in the environment. Every line of the command line of sakbackup goes through the same
  `printable_lines` (`say!`, `tell!`), and the windows show the text of a server with `textContent` only.
- **The server.** sshd takes keys only; the containers are read only, with `NoNewPrivileges` and without capabilities
  but the one of the ingress to bind 80 and 443; the metadata service is for root only; the firewall of the cloud opens
  22, 443 and icmp. A backup is encrypted with age and takes of victorialogs only the paths of its snapshots, with no
  link in them; a restore refuses links out of the archive and special files.

## Should fix

Nothing at the moment. `SEC-47` and `SEC-48` are fixed; they are in the history of git.

## Could fix

### `SEC-16` A deploy takes the root domain from the running server

A deploy "as it is" completes its settings from `GET /api/v1/deployment` (`DeploymentManager::detected`,
`Detected::from_running`, `complete` in `detect.rs`), and `resolve_dns` puts the root domain of the server before the
labels of the project. Root on the server, without a token of the cloud, can answer another zone of the same project
or the dns mode `domain`; the next deploy makes the records of the deployment in that zone, pointing at the server.
`check_root_domain` asks for a zone of the project, `check_records_in_the_way` refuses records that are there already.
The delete of the old records asks first (`WillDestroy`, `cli/deploy.rs`, `confirmPlan` of the window), unless
`--yes`; the new records in the other zone ask nothing.

Fix: the dns mode and the root domain from the labels, an answer of the server that differs is an error; from the
server only the user mode and the mail server.

### `SEC-51` The size of a gif is the size of its first frame only

`ImageMetadata.imageIoSize` takes `reader.getWidth(0)` and `getHeight(0)`; for a gif the JDK gives the frame 0 there,
not the logical screen (up to 65535 × 65535) and not the other frames. `FileService.check` then lets through a gif
larger than 2048 px. The server stays safe: it decodes frame 0 only. A client gets the original for a size above
1024 or for no size; the Android app (`RemoteImage` in `android/core/.../ui/Photos.kt`) decodes it with
`BitmapFactory.decodeByteArray` without `inSampleSize`.

Attack: a user puts such a gif as the photo of an item of a list that another user shares with them (`LIST-2`). The
other user opens the photo, and the Android app most likely ends with an `OutOfMemoryError`. What Android and the
browsers do with such a gif was not tried.

Fix: the logical screen and every frame checked, or a gif of more than one frame refused; the clients decode with a
limit.

### Small items

- `SEC-26` The host filters nothing that comes in: the table of nftables has rules for the metadata service only,
  the firewall of the cloud is the only one. Today only sshd and caddy listen on a public address.
- `SEC-29` A user has no quota in the file storage and its files have no delete (`DM-6` of
  [the review of the data model](data-model-review.md)): a user fills the data volume in steps of 10 MiB. The change
  streams (`…/changes`) have no largest number for a user and stay open when the session ends.
- `SEC-55` The other stores have no quota either. The restic api takes files of up to 512 MiB, in any number of
  repositories of a user; only `WriteGuard` (2 GiB free) stops it, and then the files, the buckets and the backups of
  every user fail with 507. With the S3 storage turned on, the restic store has no guard and the cost has no top. The
  rows of the database have no number limit, and no `WriteGuard` counts inodes: many `CreateMultipartUpload` or small
  PUTs of a bucket key use up the inodes of the volume while bytes are free.
- `SEC-56` Not all data at rest is encrypted or accepted: the private keys of the certificates and the account key of
  ACME (`ingress/`), the access and error logs of caddy, the store of victorialogs and the checkpoints of vector are
  plain on the data volume and in no entry of ACCEPTED-RISKS. Their modes are right.
- `SEC-57` SigV4 is looser than AWS (`SigV4Verifier.kt`): `host` need not be a signed header (an empty list is taken),
  and the date, region and service of the scope are not compared with `x-amz-date` and `s3`. The secrets are of this
  deployment only, so little follows; a request seen can be sent again within 15 minutes, as with S3.
- `SEC-58` All four containers are uid 10000 on the host, without a user namespace: only the mounts keep caddy, the
  backend, vector and victorialogs apart, and an escape from vector or victorialogs (third party images) gets all
  data. Only the backend has a memory limit.
- `SEC-59` Any app on the phone creates a note: the main activity of SAK Notes takes `SEND` and `SEND_MULTIPLE`
  (`MainActivity.receive`) and makes the note without a question. `EXTRA_STREAM` may be a `file:` url of the app's own
  directory or a url of its own provider (`io.pelle.sak.notes.photos`), which SAK Notes opens with its own rights, so
  e.g. the last photo of its camera goes into a note of the user. Only images are taken, into the account of the user.
  Fix: refuse `file:` and the own authority, or show the note before it is made.
- `SEC-60` On linux a web page in the browser of the user can end a desktop sign-in: the start page is `/start`
  without a secret, `fetch('http://127.0.0.1:N/start', {mode: 'no-cors'})` comes from the same user, and a second
  `/start` ends the sign-in (`Interfered`, `sak-desktop-shared/src/session.rs`). The loopback checks no `Host` and no
  `Sec-Fetch-*`. No way to get the state or a session was found. Fix: refuse a `Host` that is not
  `127.0.0.1:<port>`, ignore a `/start` that is not a navigation.
- `SEC-61` The server sets how much a desktop app reads: `api::all` (`sak-desktop-shared/src/api.rs`) asks for pages
  while a page has items and a `total` is not reached; the bodies of the api, of the sign-in and of the deployment
  (`sak-admin-cli/.../core/config.rs`) are read whole; the backup stream (`core/backup.rs`) goes to the disk without a
  limit. Only the timeouts limit them. `read_excludes` of sakbackup (`gui/commands.rs`) reads any path the window
  gives, all of it before it checks the 64 KiB. Fix: a largest number of pages, limits of the bodies, a `take` before
  the check.
- `SEC-62` Some types with a secret show it in `toString()`: `SessionToken`, `ConnectToken` (`TokenService.kt`),
  `ExchangeRequest`, `ConnectExchangeRequest`, `ExchangeResponse`, `ResticTokenResponse` and `ResticLogin`
  (`ResticRepositoryRepository.kt`). None is logged today; the rule of `AGENT.md` ("Privacy in the code") asks for it.

## Not checked

- Nothing ran against a deployment, a caddy, a victorialogs, a phone or a desktop. What caddy does (the fields of its
  access log, that it replaces `X-Forwarded-For`, that it hides `Authorization`), what the JDK does with a progressive
  jpeg, and what Android and the browsers do with a large gif are from their documentation or their source.
- Whether caddy or Ktor make `..` segments of a path shorter, so that `api.<base>/x/../../api/…` goes past the
  rewrite of the S3 api; the same authentication applies there, only the body limit is larger.
- Whether the browser keeps `/signin?token=…` and the callback of a desktop sign-in in its history before the page
  replaces the address.
- That libwebp refuses a VP8X canvas smaller than its frame; the server checks the canvas only.
- Whether netavark sets `route_localnet` on the network of the logs; that the containers there do not reach the
  loopback of the host rests on their missing capabilities.
- Whether GNU tar of the server refuses to write through links when it restores a backup that somebody made.
- The script `sak-backend` on the server and the callback plugin `http_notify.py` of ansible line by line, the tray of
  sakbackup, the inside of the sigstore check, the provisioners of the cloud crate.
- macOS and windows: what the Keychain and the Credential Manager give to other programs, and the desktop sign-in
  without the check of the user of the peer.
- The advisories of the versions of Angular, swagger-ui, lean-qr and the crates; that is for
  [the review of the supply chain](supply-chain-review.md).
- Whether the volume of the cloud provider is encrypted.
