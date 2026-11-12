---
title: "Review: security"
linkTitle: "Security"
description: "The security review of the whole application: the login and the sessions, the routes and the data of the backend, the S3 api and the ingress, the web app, the Android apps, sakctl and the server it builds."
weight: 8
---

# Review: security

**Created:** 2026-11-12

Scope: the whole application. The login and the sessions (`sak-auth`, `sak-api`), the routes and the data of the
backend (`sak-backend`, `sak-model`, `sak-openrouter`, `sak-logs`), the static hosting with the S3 api, the storage and
the ingress (`sak-s3`, `sak-storage`, `sak-ingress`, `sak-dns`), the web app (`sak-frontend`), the Android apps
(`android/`), sakctl (`sak-cli`) and the server it builds (`ansible/`). The question: what can somebody do who should
not, from the internet without a session, as a user of a multi user instance, with one key of a bucket, on the
machine of the admin or in one container of the server.

Not in scope: what [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts, unless the code is weaker than it says, and
the signing of the releases and the images, which is in [the review of the supply chain](supply-chain-review.md).

How it was done: the code was read, route by route; nothing ran against a deployment. `SEC-1` and `SEC-2` were shown
with small programs under the JVM options of the backend, not with the backend itself. Where a finding rests on an
estimate or on what caddy, podman or victorialogs do, it says so.

Nothing found lets somebody without a session read data, get a session or get a role. The two findings to fix first
both end the backend, which systemd then starts again.

## What is good

- **Who sees what.** Every query of the notes, labels, files, grocery lists and todo lists has the owner or
  `visible(user)` in its sql (`MODEL-6`); an id of another user in a path or a body is a 404 or a 422. The change
  stream carries the id of a list only, to the users who see it. Every route below `/api/v1` but `/info` and the
  login is below `authenticate` and `requireRole`; the hosting, the redirects, the users, the settings and the health
  are for an admin only, `/deployment` for a signed request only.
- **Sql and LogsQL.** Only the jOOQ DSL, no sql built from strings, no `ORDER BY` from a request; the search of the
  access logs is escaped, quoted and takes no control characters.
- **Files.** The type of a file comes from its first bytes, never from the client, and is jpeg, png, gif, webp or
  wav: no html and no svg can be stored. The paths of the blobs come from a hash or a UUID.
- **Tokens.** ES256 with a key made on the server, the issuer and the use of a token checked, so a login token is
  no session; the roles are checked again with every request, a deleted user is refused at once. No cookies, so no
  CSRF; CORS allows the origins of the web app only.
- **The login.** A link request answers 202 before it looks at the address; the host of the link comes from the
  configuration, not from the request; the password is compared in constant time; the token leaves the address bar
  and is deleted from the logs of caddy.
- **Signed requests.** The signature covers the method, the configured host, the path, the query, the time and a
  nonce; a nonce is kept only after the signature held.
- **Buckets.** A key reaches its own bucket only, checked with every operation; a key is split into segments and
  `.`, `..` and NUL are refused; presigned urls are refused; the sites are another origin than the web app and are
  served from the disk, which caddy mounts read only. caddy issues certificates only for hosts an admin named.
- **The web app.** No `innerHTML`, all text of users is interpolated; the token goes only to `/api/v1/` of its own
  host; the service worker caches no answer of the api; the content security policy allows scripts of the page only.
- **The Android apps.** The session is encrypted with a key of the keystore (AES-GCM), no backup, no cleartext, the
  certificates checked by the platform, only the main activity exported, no WebView.
- **sakctl.** The keys are derived with a purpose each, the private ssh key goes to the agent through stdin only
  (`SECURITY-2`), the host key is checked from the first connection on, the files of a run are in a directory only
  the user reads, the web view loads its own pages only and has no plugin command.
- **The server.** sshd takes keys only; the containers run as uid 10000, read only, without capabilities; the
  backend, victorialogs and the admin api of caddy listen on loopback; the firewall of the cloud opens 22, 443 and
  icmp.

## Must fix

### `SEC-1` A recording of 44 bytes ends the backend with every request for its thumbnail

Any signed in user. `POST /api/v1/files` with a WAV header of 44 bytes (PCM, 16 bit, 32767 channels, no data) is
stored: `FileService.check` looks at the length of a recording only, here 0 seconds. `GET /api/v1/files/{id}/thumbnail`
then draws the waveform, and `peaks` sizes its block from the header:
`ByteArray(frameSize * WAVEFORM_BLOCK_FRAMES)`, 65534 × 16384 bytes, 1 GB. The backend runs with `-Xmx192m` and
`-XX:+ExitOnOutOfMemoryError`, so the process ends; systemd starts it after 5 seconds. `decodeThumbnail` catches
`IOException` and `UnsupportedAudioFileException` only, so no `.no-thumbnail` marker is written and every further
request ends the backend again, as does every view of a note with the recording. 2048 channels make a block of
64 MB, a few of them at once do the same.

Shown with a small program under the JVM options of the backend: `AudioSystem` takes the header (0 frames, a frame of
65534 bytes) and the allocation ends the JVM.

Fix: `check` takes only what the apps record (PCM of 8 or 16 bit, one or two channels, a rate in a sane range);
`peaks` sizes its block in bytes, not in frames; `decodeThumbnail` writes the marker for every failure of a decoder.

### `SEC-2` Two password logins at the same moment can end the backend

Nobody has to be signed in. `POST /api/v1/auth/login/password` takes from its limits (10 an hour for each client, 60
an hour for all) and then checks the password with `withContext(Dispatchers.Default) { password.matches(…) }`: an
argon2id of 64 MiB, which BouncyCastle allocates on the heap. The limits count requests, not how many run at once;
`Dispatchers.Default` has two threads on the two cores of the server. Two hashes are 128 MiB of a heap of 192 MB.

Shown with a small program under the JVM options of the backend: two hashes at once pass with 40 MB of other live
data and end the JVM with 60 MB; the memory review measured about 100 MB at the peak. Not shown with the backend
itself. After the restart every `RateLimiter` is empty, so the 60 an hour do not bound it.

Fix: one check at a time (a `Mutex` or `limitedParallelism(1)`), a request that finds one running answers 429; count
the 64 MiB in the heap.

## Should fix

### `SEC-3` The password of the deployment is typed into the web login

`SECURITY-1` says the backend has only the hash of the admin password. At rest that is so; but the password that is
checked is the password of the deployment itself (`keys.rs`, `admin_password_hash`), and the web app and the Android
apps send it to `POST /api/v1/auth/login/password`. Whoever runs code in the backend or in caddy, or gets the admin
to type it into a page that is not SAK (`SEC-8`), reads it with the next login. From the name and the password
follow the ssh client key (root on the server, port 22 is open), the api key, the key of every backup, old ones
included, and the key of the local settings. The purposes of the keys do not help, what they are all derived from is
handed over. ACCEPTED-RISKS has only the guessing offline; [FDR-007](../fdr/FDR-007-backup.md) says the server never
has the key that decrypts a backup.

Fix: a password for the login of its own, derived by sakctl with a purpose (`admin-login`) and shown by it, so the
password of the deployment never leaves the machine of the admin; or a login where the server never sees the
password (OPAQUE). Until then an entry in ACCEPTED-RISKS, and `SECURITY-1` and FDR-007 say it (the rule changes
only with the consent of the user).

### `SEC-4` A copied session renews itself without an end

ACCEPTED-RISKS says a copied token stays valid for the time of a session. With the QR code it is longer:
`POST /api/v1/auth/connect` with the copied session gives a connect token, `POST /api/v1/auth/login/connect` a new
session of the full 30 days, of the same user, and so on, 10 an hour. The only checks are that it is no signed
request and `codesPerUser`. The page that tells when an app used a code is the page that asked for it, so the user
sees nothing. A user an admin deletes is refused; for an admin nothing ends it but a new signing key in the
database, which no route and no page does.

Fix: a claim with the time of the login the user made (carried through every connect), and no session past a
largest age from it; or one time for each user, "sessions from before are not valid", checked against `iat`, which
also gives a sign out everywhere without a list of sessions. At least the entry in ACCEPTED-RISKS says it.

### `SEC-5` The limits of a client count one IPv6 address, and the limiter grows with every key

`clientAddress()` is the whole address, and a host has a /64 of them. Three things follow:

- 60 wrong password logins an hour, each from another address, use up `attemptsPerHour`; the limit of a client (10)
  never holds. The admin of a single user instance, whose only login it is, gets 429 as long as one request a minute
  comes. Sessions that exist keep working. ACCEPTED-RISKS has this for the login links only.
- For the links ACCEPTED-RISKS says 12 client IPs can block the logins of an hour; it is one host.
- `RateLimiter.tryAcquire` is synchronized, walks all keys with every call and keeps a key for an hour. The limit of
  the client is taken first, also when the limit of all is used up, and `/auth/login/link` and `/auth/login/connect`
  make a key for every valid body. Many addresses make many keys, on a heap of 192 MB, and every request waits for
  the walk. The size was estimated, not measured.

Fix: the key of a client is its network (/64, `maskedAddress` of `ApiSignature` has it); a largest number of keys;
old entries removed for the key that is asked and by a sweep now and then, not with every call.

### ~~`SEC-6` A login link asked for the web app signs an Android app in~~

**Fixed:** `exchangeLoginToken` refuses a nonce for a token without one, so a link asked for the web app signs in no
app that waits for its own; the nonce does not use the link up.

A user of a multi user instance against another user. `exchangeLoginToken` compares the nonce only when the token
has one (`expected != null && …`), and a link asked with `client=web` has none. The attacker asks a link for their
own address, and gets the phone of the victim to open `io.pelle.sak.notes://signin?token=…` (a page, another app)
while the app of the victim waits for a link of its own (15 minutes). The app sends its nonce, the backend ignores
it, and the app is signed in as the attacker: what the victim writes goes to the account of the attacker.
`LoginRequest.kt` says the nonce is there so that this can not be.

Fix: `exchangeLoginToken` refuses a nonce for a token without one; better, the client is in the token and the
exchange names its client. A test for it, `AuthRoutesTest` has only the wrong nonce.

### `SEC-7` A login link signs in whatever browser opens it, over a session that is there

The same attacker. The web app sends no nonce with `requestLogin`, and `Signin.ngOnInit` exchanges the token of
`/signin?token=…` at once: no check that this browser asked, no question when another user is signed in. A link the
attacker sends to the admin replaces the session of the admin with the one of the attacker and goes to a page of the
attacker's choice inside the app.

Fix: the web app sends a nonce too and keeps it with the request, at the cost that a link opens only in the browser
that asked; or the page asks ("sign in as …?") when this browser did not ask or another address is signed in.

### `SEC-8` A scanned QR code is trusted without a question when the app is signed out

`signInWithQr` shows the dialog with the two addresses only when a session exists; a signed out app, also one that
has a deployment stored, exchanges at once with whatever host the code names and stores it as its SAK. A host of an
attacker accepts any token. What the user then adds goes there; or the host answers 401, the app shows its sign-in
with the password field for that host, and a user who types the password of the admin has given away the password
of the deployment (`SEC-3`). The same way a user of the instance signs a signed out phone into their own account
with a code of their own.

Fix: always name the host and ask before the exchange; when a deployment is stored and the code names another, the
dialog that is there for a session.

### `SEC-9` One key of a bucket can fill the data volume

HOSTING-7 says a leaked key pair gives away one site. It can stop the deployment: the route of the S3 api in caddy
has no largest body (the comment in `SakApps.kt` says so), `putObject` and `uploadPart` write what comes, a part
number is checked only for `< 1`, and the parts of an upload that is never completed or aborted are never removed
(`removeStale` is for `.tmp` only). The volume also has the database, the certificates of caddy and the logs. The
statistics of a bucket count `data/` only, so the admin sees nothing grow.

Fix: refuse a write when the volume has less than a floor free, or a largest size for each bucket; part numbers to
10000; uploads older than a day removed at the start and with the cleanup
([FDR-004](../fdr/FDR-004-maintenance-cleanup.md)); `uploads/` counted in the usage.

### `SEC-10` Many user agents on a site can end the backend when the admin opens its access logs

Anybody who can send requests to a site of a bucket. `SiteMetricsService` asks
`stats by (user_agent) count()` without a limit of the groups, `VictoriaLogsClient` reads the whole answer as a
string and parses it to a tree. vector cuts a user agent at 512 characters, which bounds one, not how many. About
100,000 different ones in the range the admin looks at are an answer of some 60 MB, held three times. Estimated, not
measured; whether victorialogs limits the series itself was not checked.

Fix: vector stores the group of the user agent, and the query groups by it; or a limit in the query; both clients
read an answer through a largest size.

### `SEC-11` Secrets of an old setup are in the working copy

Not a finding of the code: `terraform/modules/hcloud/backend/` is ignored by git and was never committed, but it
still has a private ssh key (`.ssh/id_ed25519`), `.ansible/backend.env` and `.ansible/credentials/`, all readable by
the user only. The repository has no terraform any more.

Fix: delete the directory; replace the keys if they are still good anywhere.

## Could fix

### `SEC-12` The key pair of a bucket can not be replaced

There is no route that makes a new key pair; a leaked one ends only with the bucket, whose name stays taken
(`MODEL-3`), after every redirect to it is changed. So a leaked key is likely left as it is.

Fix: `POST /api/v1/static-hosting/{bucket}/secret` writes a new access key id and secret.

### `SEC-13` A copy in the S3 api writes an empty object

`PUT /<bucket>/<key>` with `x-amz-copy-source` goes to `putObject`: the header is read nowhere, the empty body
verifies, and an object of 0 bytes replaces the target with a 200. A copy of a key onto itself (a change of its
metadata) destroys it. No attack, a loss of data for a client that copies on the server.

Fix: 501 `NotImplemented` when the header is there.

### `SEC-14` The containers share the network of the host

All four have `Network=host`. What listens on loopback has no login: the admin api of caddy (`localhost:2019`), the
backend without caddy before it (8080), victorialogs (9428). Code that runs in one container, vector and
victorialogs are images of others, can load a configuration of its own into caddy and so read the keys of the
certificates and all traffic, the password of `SEC-3` included. What the admin api allows is from the documentation
of caddy, not tried.

Fix: the admin api on a unix socket in a directory only the ingress and the backend mount; or a network of their own
for vector and victorialogs.

### `SEC-15` A restore trusts the archive as root

`sak-backend restore` extracts as root with `--numeric-owner --same-permissions`, moves every entry of the top
level to the data volume whatever its name, and opens the database of the backup as root for the integrity check
(the backup uses `runuser -u sak`). A backup is encrypted to a public key, so it proves no sender; `check` in sakctl
shows only that it decrypts. The volume is mounted without `nosuid,nodev`. Whoever was root when a backup was made
puts setuid files and an sqlite file of their choice onto the server that is built from it. No way to root from
there was found: tar refuses `..`, three of the four containers have `NoNewPrivileges`.

Fix: `nosuid,nodev` for the volume; extract without owners, then `chown` to `sak` and no special bits; only the known
entries of the top level; the integrity check as `sak`.

### `SEC-16` A deploy takes the root domain from the running server

A deploy "as it is" completes its settings from `GET /api/v1/deployment` (`Detected::from_running`), and the dns
mode and the root domain of the server come before the labels of the project. Root on the server, without a token
of the cloud, can answer another zone of the same project; the next deploy plans the records of the deployment in
that zone, pointing at the server. `check_root_domain` asks for a zone of the project without a conflict, the plan
shows the change, the command line asks only before it destroys. Whether the removal of the old records counts as
that was not traced.

Fix: the dns mode and the root domain from the labels, an answer of the server that differs is an error; from the
server only the user mode and the mail server.

### `SEC-17` The password of the mail server read from the server reaches the web view of sakctl

`deployment_settings` answers `DetectedSmtp` with its password to the page. ACCEPTED-RISKS names only the passwords
of the environment. The same things limit it: the policy of the web view, values written as text.

Fix: answer `passwordSet` only, the password stays in `LoadedSettings`; or the entry says it.

### `SEC-18` Signing out of the web app keeps the data of the user in the page

`signOut` and `sessionRejected` navigate to `/login` without a reload. The stores are of the root (`NotesStore`, the
blob urls of the thumbnails) and are not emptied: the next user of the tab sees the notes of the one before until
their own answer comes, and under the error when it fails. Traced for the notes; the other stores are built alike.

Fix: load the page anew on a sign out (`location.assign('/login')`).

### `SEC-19` A user can test the names of the private lists of another

A user who sees a shared list renames it (`LIST-2`). The names are unique among all lists of the owner, the private
ones too, and the rename of another user answers 409 with a text of its own (`OwnerNameTaken`): a 409 says the owner
has a list of that name.

Fix: only the owner renames, or the names of the shared lists are unique apart from the private ones. Another text
does not help, the 409 tells it.

### `SEC-20` What OpenRouter answers has no largest size

No request sets `max_tokens`, the answer is read whole (`BodyHandlers.ofString()`), and `FileTextService` stores the
text as it comes; sqlite does not hold the `varchar(100000)` of the column. An image that makes the model write a
lot costs money, 30 times an hour for each user. `OpenRouterSpeechTranscriber` answers 500 instead of 502 for an
answer of another shape (`jsonObject`, `jsonPrimitive` throw).

Fix: `max_tokens`, a largest length of the stored text, the answer read through a largest size.

### `SEC-21` The logs have more than they should

- `allowLoginLink` logs the whole address of the client next to the hash of the user ("not sending another login
  link to … for …"); everywhere else it is masked (`maskedAddress`, `ip_mask 24 64` in the Caddyfile).
- The query of a request to the api is stored with the access logs (vector keeps `request.uri`, 2048 characters):
  what a user types into the field of a grocery list (`suggestions?text=…`), the label notes are filtered by, the
  search of the admin. No route serves the logs of the api, they are on the server only.

Fix: `maskedAddress(client)`; vector drops the query for the sites of the api and the web app.

### `SEC-22` What is used once is forgotten with a restart

The used login tokens and connect tokens, the nonces of the signed requests and every limit are in memory. After a
restart a used link or code works once more within its time, the page of a code can not say it was used (404), a
relayed signed request can be sent a second time within its two minutes, and the limits start again. A deploy
restarts the backend; so do `SEC-1` and `SEC-2`. The code says it for the links; ACCEPTED-RISKS says a code is good
once.

Fix: the used ids in a small table with their end; or an entry in ACCEPTED-RISKS.

### Small items

- `SEC-23` A streamed upload (`STREAMING-…`) is not bound to its signature: the signatures of the chunks and the
  checksums of the trailer are not checked (the code says so), the signed headers allow any body for 15 minutes. It
  takes the headers, which means breaking TLS.
- `SEC-24` sakctl wipes less than `Cargo.toml` says: `expand` returns a plain array, the age keys as strings and
  the password in `DeploymentRequest` and in the JSON to the web view are not wiped. Memory of the process only.
- `SEC-25` The lines of the server and of ansible go to the terminal as they come (`cli/server.rs`, `core/ssh.rs`);
  a server that is taken over can send escape sequences. Fix: no control characters in a line.
- `SEC-26` The host filters nothing that comes in: the table of nftables has the rule for the metadata service only,
  the firewall of the cloud is the only one. Today only sshd and caddy listen on a public address.
- `SEC-27` The drop-in of sshd is `50-sak.conf`; sshd takes the first value it reads and `50-cloud-init.conf` sorts
  before it. `00-sak.conf` would win over an image that allows passwords there. Not checked on an image.
- `SEC-28` The ingress has no `NoNewPrivileges`, for the capability of the caddy binary. An ambient capability
  given by podman could allow it; to try.
- `SEC-29` A file of the file storage has no delete and a user no quota: a user fills the data volume in steps of
  10 MiB. The change streams (`…/changes`) have no largest number for a user and stay open when the session ends.
- `SEC-30` The comment at `0001-initial.yaml:511` says only the owner of a list changes its categories; `GROCERY-13`
  and the code let every user who sees it.

## Not checked

- Nothing ran against a deployment, a caddy, a victorialogs or a phone; what these do (the admin api, the
  `X-Forwarded-For` that caddy sets, the headers it leaves out of its logs) is from their documentation.
- That the XML reader of the S3 api takes no DTD and no outside entity is from what is known of Jackson 3, not from
  a test.
- An image of 2048 × 2048 px with 16 bit and alpha decodes to about 33 MB, twice what the memory review counts; two
  at once were not tried against the heap.
- The Android apps beyond their requests, intents and temporary files, and the pages of the grocery and todo lists
  of the web app line by line (they were searched for sinks).
- `prettier`, the tests and the e2e tests did not run for this review; it changes no code.
