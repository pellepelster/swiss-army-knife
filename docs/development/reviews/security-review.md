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

**Reviewed again:** 2026-11-26 at commit 14ed06ab, the login, the authorization of the apps, the tokens and their
lifetimes, on the server and in the clients: `SEC-22` (`#155`) is still as written, `SEC-3`
is accepted in ACCEPTED-RISKS until the login has a password of its own;
new are `SEC-40` to `SEC-46`. Fixed since: `SEC-39` (a user added again had their old sessions and tokens back) and
`SEC-7` (a login link signed in any browser over the session there; it now asks when this browser did not ask for
it or another address is signed in), `SEC-4` (a copied session renewed itself without an end): a session lasts at most 180 days from its sign-in with a
link or the password, and a user signs out everywhere. The code was read, nothing ran against a deployment.

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

## The tokens and their lifetimes

Every JWT is ES256 with one key the server makes and keeps in its settings (`SigningKey`, `TokenService`); the
verifier pins the algorithm and checks the issuer (the address of the web app), the use of the token, `exp`, `nbf`
and `iat` (without leeway, the default of java-jwt). No token has a `kid` and no route replaces the key; a sign out
everywhere ends the sessions of one user without it.

| Token | Made of | Lifetime | Used once | Kept | Ends early |
|---|---|---|---|---|---|
| Login link | JWT: the address, a random id, the redirect, the hash of a nonce (the apps) | 15 minutes | yes, the used ids in memory (`SEC-22`) | nowhere | sign out everywhere |
| Session | JWT: the address, the roles, how the user signed in; no id | 30 days, at most 180 days from the sign-in (`auth_time`, handed on through every connect) | no | on the device | sign out everywhere (`sign_outs`), the address loses its roles, is deleted or added again |
| Connect (QR code, desktop with pkce) | JWT: a random id, the challenge of pkce, the `auth_time` of its session | 2 minutes | yes, in memory; a wrong verifier does not use it up | nowhere | sign out everywhere |
| Repository of restic | 32 random bytes | none | no | plain in the database (accepted) | when the repository goes or its owner loses the roles; a new one when the owner is deleted |
| Signed request of sakadmin | Ed25519 over method, host, path, query, time, nonce | ±120 seconds | yes, the nonces in memory (`SEC-22`) | nowhere | no |

The limits, all in memory: a login link 5 an hour per address and client, 20 per client, 60 in all; the password 10
an hour per client and 60 in all; the exchange of a connect token 30 an hour per client; 10 QR codes an hour per user;
30 wrong tokens of a repository of restic an hour per repository and client. A client is an IPv4 address or an IPv6
/64, from the last `X-Forwarded-For` that caddy sets.

## Should fix

Nothing at the moment.

## Could fix

### `SEC-42` A signed request is an admin for every method, not only to read

The comment of `ApiSignature` says "sakadmin only reads", but a valid signature gives an admin that never expires
(`setOf(Role.ADMIN)`) to every route, and only a body is refused: `DELETE /api/v1/users/{id}`, a redirect or a
repository of restic can be deleted with one. Whoever relays a fresh signed request (accepted in ACCEPTED-RISKS) gets
only what sakadmin asked for; but the key is the strongest credential of the deployment and nothing of sakadmin needs
more than `GET`. The check for a body rests on `Content-Length` and `Transfer-Encoding`; that caddy hands every body
on with one of them was not checked.

Fix: a signed request for `GET` and `HEAD` only, a route that needs more says so.

### `SEC-16` A deploy takes the root domain from the running server

A deploy "as it is" completes its settings from `GET /api/v1/deployment` (`Detected::from_running`), and the dns
mode and the root domain of the server come before the labels of the project. Root on the server, without a token
of the cloud, can answer another zone of the same project; the next deploy plans the records of the deployment in
that zone, pointing at the server. `check_root_domain` asks for a zone of the project without a conflict, the plan
shows the change; the command line and the window ask before a plan that deletes or replaces (`DA-26`), a plan that
only changes records asks nothing. Whether the removal of the old records counts as a delete was not traced.

Fix: the dns mode and the root domain from the labels, an answer of the server that differs is an error; from the
server only the user mode and the mail server.

### Small items

- `SEC-26` The host filters nothing that comes in: the table of nftables has rules for the metadata service only,
  the firewall of the cloud is the only one. Today only sshd and caddy listen on a public address.
- `SEC-29` A user has no quota in the file storage and its files have no delete (`DM-6` of
  [the review of the data model](data-model-review.md)): a user fills the data volume in steps of 10 MiB. The change
  streams (`…/changes`) have no largest number for a user and stay open when the session ends.

## Not checked

- For the review of 2026-11-26: whether the browser keeps `/signin?token=…` and the callback of a desktop sign-in
  (`127.0.0.1/callback?…&token=…`) in its history before the page replaces the address; a token of a link is used
  once (but see `SEC-22`), one of a desktop is nothing without the verifier of pkce. That caddy overwrites an
  `X-Forwarded-For` from outside and that the backend is reached only through caddy, which the client of every limit
  rests on, is from the configuration and the documentation of caddy. The admin pages of the web app (`/users`,
  `/settings`, `/hosting`) have no guard of the role, only the backend refuses; that is how it looks, not a hole.

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
