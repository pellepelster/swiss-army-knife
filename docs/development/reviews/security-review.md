---
title: "Review: security"
linkTitle: "Security"
description: "The security review of the whole application: the login and the sessions, the routes and the data of the backend, the S3 api and the ingress, the web app, the Android apps, sakadmin and the server it builds, and the sign-in of the desktop apps."
weight: 8
---

# Review: security

**Created:** 2026-11-12

**Checked again:** 2026-11-23, every finding listed is still open; the fixed ones (since the last check `SEC-8` and
`SEC-38`) are in the history of git.

Scope: the whole application. The login and the sessions (`sak-auth`, `sak-api`), the routes and the data of the backend
(`sak-backend`, `sak-model`, `sak-openrouter`, `sak-logs`), the static hosting with the S3 api, the storage and the
ingress (`sak-s3`, `sak-storage-local`, `sak-ingress`, `sak-dns`), the web app (`sak-frontend`), the Android apps
(`android/`), sakadmin (`sak-admin-cli`) and the server it builds (`ansible/`). The question: what can somebody do who
should not, from the internet without a session, as a user of a multi user deployment, with one key of a bucket, on the
machine of the admin or in one container of the server.

Not in scope: what [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts, unless the code is weaker than it says, and
the signing of the releases and the images, which is in [the review of the supply chain](supply-chain-review.md).

How it was done: the code was read, route by route; nothing ran against a deployment. Where a finding rests on an
estimate or on what caddy, podman or victorialogs do, it says so. The fixed findings are in the history of git.

Nothing found lets somebody without a session read data, get a session or get a role.

**Added 2026-11-20:** the sign-in of the desktop apps (the three commits after `fcfc50ca`): the connect token with a
pkce challenge (`sak-auth`), the page `/connect-desktop` (`sak-frontend`), `sak-desktop-shared` (the sign-in through the
browser, `instances.json`, the keychain) and the window of sakbackup. The question for it: what can another program or
another user of the machine do, a link somebody sends, or a host on the way. Its finding is `SEC-36`; the others it
had are fixed. The code was read; the sign-in ran against `local:run` with a script as the browser, not with a
person in a browser. Nothing found gives a session to somebody who is not on the machine of the user.

## What is good

- **Who sees what.** Every query of the notes, labels, files, grocery lists and todo lists has the owner or
  `visible(user)` in its sql (`MODEL-6`); an id of another user in a path or a body is a 404 or a 422. The change
  stream carries the id of a list only, to the users who see it. Every route below `/api/v1` but `/info` and the
  login is below `authenticate` and `requireRole`; the hosting, the redirects, the users, the settings and the health
  are for an admin only, `/deployment` for a signed request only.
- **Sql and LogsQL.** Only the jOOQ DSL, no sql built from strings, no `ORDER BY` from a request; the search of the
  access logs is escaped, quoted and takes no control characters.
- **Files.** The type of a file comes from its first bytes, never from the client, and is jpeg, png, gif, webp or
  wav: no html and no svg can be stored. The paths of the blobs come from a resource id (`MODEL-8`) or a UUID, never
  from a name the client gives.
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
- **sakadmin.** The keys are derived with a purpose each, the private ssh key goes to the agent through stdin and is
  written only by `sakadmin ssh-config`, to the directory the user names (`SECURITY-2`), the host key is checked from
  the first connection on, the files of a run are in a directory only the user reads, the web view loads its own pages
  only and has no plugin command.
- **The sign-in of a desktop app.** The connect token is bound to the pkce challenge of the app: without the
  verifier it is a 401, a wrong verifier does not use it up, a token of a QR code is refused with a verifier and the
  other way round (`ConnectTest`). The page sends the browser to `127.0.0.1` only, with a port that is a number; it
  asks for the token only after a click, and it can not be framed (`frame-ancestors 'none'`). The app takes the
  callback only with its state of 128 bits, answers everything else 404, and listens on the loopback only. The api
  is https unless the SAK address is on the loopback. The token is in no file, no log and no error text; the page
  of the app on the loopback escapes its texts and loads nothing. caddy writes no query, so the state is in no log
  of the server. The window has no plugin command, the browser opens only on an http or https url the app built.
- **The server.** sshd takes keys only; the containers run as uid 10000, read only, without capabilities but the one of
  the ingress to bind 80 and 443; the backend, victorialogs and the admin api of caddy listen on loopback; the firewall
  of the cloud opens 22, 443 and icmp.

## Should fix

### `SEC-3` The password of the deployment is typed into the web login

`SECURITY-1` says the backend has only the hash of the admin password. At rest that is so; but the password that is
checked is the password of the deployment itself (`keys.rs`, `admin_password_hash`), and the web app and the Android
apps send it to `POST /api/v1/auth/login/password`. Whoever runs code in the backend or in caddy, or gets the admin
to type it into a page that is not SAK, reads it with the next login. From the name and the password
follow the ssh client key (root on the server, port 22 is open), the api key, the key of every backup, old ones
included, and the key of the local settings. The purposes of the keys do not help, what they are all derived from is
handed over. Accepted in [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) until it is fixed; the backup says the server
never has the key that decrypts a backup.

Fix: a password for the login of its own, derived by sakadmin with a purpose (`admin-login`) and shown by it, so the
password of the deployment never leaves the machine of the admin; or a login where the server never sees the
password (OPAQUE). Until then `SECURITY-1` says it (the rule changes only with the consent of the user).

### `SEC-4` A copied session renews itself without an end

A copied token stays valid for the time of a session. With the QR code it is longer, which ACCEPTED-RISKS accepts
until it is fixed:
`POST /api/v1/auth/connect` with the copied session gives a connect token, `POST /api/v1/auth/login/connect` a new
session of the full 30 days, of the same user, and so on, 10 an hour. The only checks are that it is no signed
request and `codesPerUser`. The page that tells when an app used a code is the page that asked for it, so the user
sees nothing. A user an admin deletes is refused; for an admin nothing ends it but a new signing key in the
database, which no route and no page does.

Fix: a claim with the time of the login the user made (carried through every connect), and no session past a
largest age from it; or one time for each user, "sessions from before are not valid", checked against `iat`, which
also gives a sign out everywhere without a list of sessions.

To decide: the largest age of a session (proposed: 90 days from the login the user made, carried through every
connect), whether the sign out everywhere comes with it, and the change of `SECURITY-3` and of ACCEPTED-RISKS (a rule
changes only with the consent of the user).

### `SEC-7` A login link signs in whatever browser opens it, over a session that is there

The same attacker. The web app sends no nonce with `requestLogin`, and `Signin.ngOnInit` exchanges the token of
`/signin?token=…` at once: no check that this browser asked, no question when another user is signed in. A link the
attacker sends to the admin replaces the session of the admin with the one of the attacker and goes to a page of the
attacker's choice inside the app.

Fix: the web app sends a nonce too and keeps it with the request, at the cost that a link opens only in the browser
that asked; or the page asks ("sign in as …?") when this browser did not ask or another address is signed in.

## Could fix

### `SEC-16` A deploy takes the root domain from the running server

A deploy "as it is" completes its settings from `GET /api/v1/deployment` (`Detected::from_running`), and the dns
mode and the root domain of the server come before the labels of the project. Root on the server, without a token
of the cloud, can answer another zone of the same project; the next deploy plans the records of the deployment in
that zone, pointing at the server. `check_root_domain` asks for a zone of the project without a conflict, the plan
shows the change; the command line and the window ask before a plan that deletes or replaces (`DA-26`), a plan that
only changes records asks nothing. Whether the removal of the old records counts as a delete was not traced.

Fix: the dns mode and the root domain from the labels, an answer of the server that differs is an error; from the
server only the user mode and the mail server.

### `SEC-17` The password of the mail server read from the server reaches the web view of sakadmin

`deployment_settings` answers `DetectedSmtp` with its password to the page. Accepted in ACCEPTED-RISKS until it
is fixed, with the passwords of the environment. The same things limit it: the policy of the web view, values written
as text.

Fix: answer `passwordSet` only, the password stays in `LoadedSettings`.

### `SEC-20` What OpenRouter answers has no largest size

No request sets `max_tokens`, the answer is read whole (`BodyHandlers.ofString()`), and `FileTextService` stores the
text as it comes; sqlite does not hold the `varchar(100000)` of the column. An image that makes the model write a
lot costs money, 30 times an hour for each user.

Fix: `max_tokens`, a largest length of the stored text, the answer read through a largest size.

### Small items

- `SEC-23` A streamed upload (`STREAMING-…`) is not bound to its signature: the signatures of the chunks and the
  checksums of the trailer are not checked (the code says so), the signed headers allow any body for 15 minutes. It
  takes the headers, which means breaking TLS.
- `SEC-24` sakadmin wipes less than `Cargo.toml` says: `expand` returns a plain array, the age keys as strings and
  the password in `DeploymentRequest` and in the JSON to the web view are not wiped. Memory of the process only.
- `SEC-26` The host filters nothing that comes in: the table of nftables has rules for the metadata service only,
  the firewall of the cloud is the only one. Today only sshd and caddy listen on a public address.
- `SEC-36` The session token of a desktop app is a plain `String` in the `SessionManager` and on its way to the
  keychain, not wiped when it goes (as `SEC-24` for sakadmin); the state of a callback and the secret of the start page
  off linux are compared with `!=` and `==`, not in constant time (128 bits, on the loopback: no practical way to
  measure them).
- `SEC-29` A user has no quota in the file storage and its files have no delete (`DM-6` of
  [the review of the data model](data-model-review.md)): a user fills the data volume in steps of 10 MiB. The change
  streams (`…/changes`) have no largest number for a user and stay open when the session ends.

## Not checked

- Nothing ran against a deployment, a caddy, a victorialogs or a phone; what these do (the admin api, the
  `X-Forwarded-For` that caddy sets, the headers it leaves out of its logs) is from their documentation.
- That the XML reader of the S3 api takes no DTD and no outside entity is from what is known of Jackson 3, not from
  a test.
- An image of 2048 × 2048 px with 16 bit and alpha decodes to about 33 MB, twice the 17 MB `FileService` counts for a
  decoded photo; as many decodes as the server has processors run at once, which was not tried against the heap.
- The Android apps beyond their requests, intents and temporary files, and the pages of the grocery and todo lists
  of the web app line by line (they were searched for sinks).
- `prettier` did not run for this review, it changes no code; the tests and the e2e tests ran with the check of
  2026-11-22.
- For the desktop sign-in (2026-11-20): the window was not driven by a person, and macOS and windows not at all: what
  the Keychain and the Credential Manager give to other programs, and whether their openers show the url to other users,
  is not checked. That linux refuses a connection of another user rests on the tests of the parser and on the
  sign-ins of the tests, which are of the same user; no second user tried it. That macOS and windows keep the secret
  of the start page of a sign-in from another user rests on their showing the arguments of a process (`/usr/bin/open`,
  the browser) to its user and an admin only, which was not tried on either.
- The new crates (`keyring` 4.2.0 with `zbus` for the Secret Service, in the lockfiles of all three desktop
  crates) were not read; that is for [the review of the supply chain](supply-chain-review.md).
- That tauri refuses the commands of the opener plugin to the web view without a capability file is from its
  documentation, as for sakadmin.
