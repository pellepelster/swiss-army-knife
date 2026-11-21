---
title: "Review: security"
linkTitle: "Security"
description: "The security review of the whole application: the login and the sessions, the routes and the data of the backend, the S3 api and the ingress, the web app, the Android apps, sakadmin and the server it builds, and the sign-in of the desktop apps."
weight: 8
---

# Review: security

**Created:** 2026-11-12

Scope: the whole application. The login and the sessions (`sak-auth`, `sak-api`), the routes and the data of the
backend (`sak-backend`, `sak-model`, `sak-openrouter`, `sak-logs`), the static hosting with the S3 api, the storage and
the ingress (`sak-s3`, `sak-storage`, `sak-ingress`, `sak-dns`), the web app (`sak-frontend`), the Android apps
(`android/`), sakadmin (`sak-admin-cli`) and the server it builds (`ansible/`). The question: what can somebody do who should
not, from the internet without a session, as a user of a multi user instance, with one key of a bucket, on the
machine of the admin or in one container of the server.

Not in scope: what [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) accepts, unless the code is weaker than it says, and
the signing of the releases and the images, which is in [the review of the supply chain](supply-chain-review.md).

How it was done: the code was read, route by route; nothing ran against a deployment. Where a finding rests on an
estimate or on what caddy, podman or victorialogs do, it says so. The fixed findings are in the history of git.

Nothing found lets somebody without a session read data, get a session or get a role.

**Added 2026-11-20:** the sign-in of the desktop apps (the three commits after `fcfc50ca`): the connect token with
a pkce challenge (`sak-auth`), the page `/connect-desktop` (`sak-frontend`), `sak-desktop-shared` (the sign-in
through the browser, `instances.json`, the keychain) and the window of sakbackup. The question for it: what can
another program or another user of the machine do, a link somebody sends, or a host on the way. Its findings are
`SEC-36` and `SEC-38`; the others it had are fixed. The code was read; the sign-in ran against `local:run` with a script as the browser, not with a
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
- **sakadmin.** The keys are derived with a purpose each, the private ssh key goes to the agent through stdin only
  (`SECURITY-2`), the host key is checked from the first connection on, the files of a run are in a directory only
  the user reads, the web view loads its own pages only and has no plugin command.
- **The sign-in of a desktop app.** The connect token is bound to the pkce challenge of the app: without the
  verifier it is a 401, a wrong verifier does not use it up, a token of a QR code is refused with a verifier and the
  other way round (`ConnectTest`). The page sends the browser to `127.0.0.1` only, with a port that is a number; it
  asks for the token only after a click, and it can not be framed (`frame-ancestors 'none'`). The app takes the
  callback only with its state of 128 bits, answers everything else 404, and listens on the loopback only. The api
  is https unless the SAK address is on the loopback. The token is in no file, no log and no error text; the page
  of the app on the loopback escapes its texts and loads nothing. caddy writes no query, so the state is in no log
  of the server. The window has no plugin command, the browser opens only on an http or https url the app built.
- **The server.** sshd takes keys only; the containers run as uid 10000, read only, without capabilities; the
  backend, victorialogs and the admin api of caddy listen on loopback; the firewall of the cloud opens 22, 443 and
  icmp.

## Should fix

### `SEC-3` The password of the deployment is typed into the web login

`SECURITY-1` says the backend has only the hash of the admin password. At rest that is so; but the password that is
checked is the password of the deployment itself (`keys.rs`, `admin_password_hash`), and the web app and the Android
apps send it to `POST /api/v1/auth/login/password`. Whoever runs code in the backend or in caddy, or gets the admin
to type it into a page that is not SAK (`SEC-8`), reads it with the next login. From the name and the password
follow the ssh client key (root on the server, port 22 is open), the api key, the key of every backup, old ones
included, and the key of the local settings. The purposes of the keys do not help, what they are all derived from is
handed over. ACCEPTED-RISKS has only the guessing offline; the backup says the server never
has the key that decrypts a backup.

Fix: a password for the login of its own, derived by sakadmin with a purpose (`admin-login`) and shown by it, so the
password of the deployment never leaves the machine of the admin; or a login where the server never sees the
password (OPAQUE). Until then an entry in ACCEPTED-RISKS, and `SECURITY-1` says it (the rule changes
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

#### Plan

Open, for the user to decide: the largest age (proposed: 90 days), whether the second step comes with it, and the
change of `SECURITY-3` and of ACCEPTED-RISKS (a rule changes only with the consent of the user).

1. **The time of the login in every session.** `TokenService` gets a claim `login_at`, the time of the login the
   user made: the exchange of a login link (`exchangeLoginToken`) and the password login set it to now.
   `issueSessionToken` takes it as a parameter, `SessionPrincipal` carries it.
2. **Carried through the QR code.** `issueConnectToken` writes the `login_at` of the session that asks for the code
   into the connect token, `exchangeConnectToken` gives it back in `ConnectClaims`, and the session of the app
   (`POST /api/v1/auth/login/connect`) gets the `login_at` of the code, not now.
3. **A largest age.** `TokenService` gets `sessionMaxAge` (default 90 days, next to `sessionTokenTtl`). A session
   expires at the earlier of now + `sessionTokenTtl` and `login_at` + `sessionMaxAge`; the answer of the exchange
   and of the connect says that time (`expiresAt`), so the clients need no change.
4. **Checked with every request.** `session()` refuses a token without `login_at` or with one older than
   `sessionMaxAge`; a connect token without it is refused too. Without backwards compatibility every user signs in
   once after the update, the apps and the web app answer the 401 with their sign-in already.
5. **Tests.** In `TokenServiceTest` and `ConnectTest`, with the test clock: a chain of codes ends at `login_at` +
   `sessionMaxAge`, whatever its length; a session of an app made on day 80 ends on day 90; a token without the
   claim is a 401; the link and the password start a new `login_at`.
6. **The texts.** The descriptions of the OpenAPI of the exchange, the password login and the connect name the
   largest age (`mise run api:generate`). `SECURITY-3`: an app gets a session of its own full time, but none past
   the largest age of the login it came from. ACCEPTED-RISKS: a copied token stays valid until it expires, at most
   the largest age after the login of the user.
7. **Second step, if wanted: a sign out everywhere.** One time for each user in the settings, "sessions issued
   before are not valid", checked in `session()` against `iat`; a route and a button set it for the user, an admin
   sets it for a user and for themselves. It is the first state of the sessions in the backend, so it is a decision
   of its own.

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

## Could fix

### `SEC-16` A deploy takes the root domain from the running server

A deploy "as it is" completes its settings from `GET /api/v1/deployment` (`Detected::from_running`), and the dns
mode and the root domain of the server come before the labels of the project. Root on the server, without a token
of the cloud, can answer another zone of the same project; the next deploy plans the records of the deployment in
that zone, pointing at the server. `check_root_domain` asks for a zone of the project without a conflict, the plan
shows the change, the command line asks only before it destroys. Whether the removal of the old records counts as
that was not traced.

Fix: the dns mode and the root domain from the labels, an answer of the server that differs is an error; from the
server only the user mode and the mail server.

### `SEC-17` The password of the mail server read from the server reaches the web view of sakadmin

`deployment_settings` answers `DetectedSmtp` with its password to the page. ACCEPTED-RISKS names only the passwords
of the environment. The same things limit it: the policy of the web view, values written as text.

Fix: answer `passwordSet` only, the password stays in `LoadedSettings`; or the entry says it.

### `SEC-20` What OpenRouter answers has no largest size

No request sets `max_tokens`, the answer is read whole (`BodyHandlers.ofString()`), and `FileTextService` stores the
text as it comes; sqlite does not hold the `varchar(100000)` of the column. An image that makes the model write a
lot costs money, 30 times an hour for each user. `OpenRouterSpeechTranscriber` answers 500 instead of 502 for an
answer of another shape (`jsonObject`, `jsonPrimitive` throw).

Fix: `max_tokens`, a largest length of the stored text, the answer read through a largest size.

### `SEC-22` What is used once is forgotten with a restart

Moved to GitHub: [#155](https://github.com/pellepelster/swiss-army-knife/issues/155).

### Small items

- `SEC-23` A streamed upload (`STREAMING-…`) is not bound to its signature: the signatures of the chunks and the
  checksums of the trailer are not checked (the code says so), the signed headers allow any body for 15 minutes. It
  takes the headers, which means breaking TLS.
- `SEC-24` sakadmin wipes less than `Cargo.toml` says: `expand` returns a plain array, the age keys as strings and
  the password in `DeploymentRequest` and in the JSON to the web view are not wiped. Memory of the process only.
- `SEC-26` The host filters nothing that comes in: the table of nftables has the rule for the metadata service only,
  the firewall of the cloud is the only one. Today only sshd and caddy listen on a public address.
- `SEC-36` The session token of a desktop app is a plain `String` in the `SessionManager` and on its way to the
  keychain, not wiped when it goes (as `SEC-24` for sakadmin); the state of a callback is compared with `!=`, not in
  constant time (128 bits, on the loopback: no practical way to measure it).
- `SEC-38` On macOS and windows every program of the machine can ask the page of a desktop sign-in on the loopback
  (`/start`), which gives the state out once; the port is found by trying the ports. Another user who asks before
  the browser of the user does, and whose callback also comes first, signs the app in as them: the browser shows a
  failed connection, the menu of the app their name. It needs an account at the same instance. They can also end
  every sign-in with a request of `/start`. On linux the app takes only connections of its own user
  (`peer_user`, from `/proc/net/tcp`), so this is closed there. To close it elsewhere the app would keep listening
  after the sign-in for a time and sign out when a second `/start` comes. sakbackup is built for linux only so far.
- `SEC-29` A file of the file storage has no delete and a user no quota: a user fills the data volume in steps of
  10 MiB. The change streams (`…/changes`) have no largest number for a user and stay open when the session ends.

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
- For the desktop sign-in (2026-11-20): the window was not driven by a person, and macOS and windows not at all:
  what the Keychain and the Credential Manager give to other programs, and whether their openers show the url to
  other users, is not checked. That linux refuses a connection of another user (`SEC-38`) rests on the tests of the parser and on the
  sign-ins of the tests, which are of the same user; no second user tried it.
- The new crates (`keyring` 4.2.0 with `zbus` for the Secret Service, in the lockfiles of all three desktop
  crates) were not read; that is for [the review of the supply chain](supply-chain-review.md).
- That tauri refuses the commands of the opener plugin to the web view without a capability file is from its
  documentation, as for sakadmin.
