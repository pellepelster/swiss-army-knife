---
title: "Backup"
description: "sak-backup, the desktop app for the backups of a SAK instance, and what it shares with sakadm."
weight: 50
---

# Backup

A desktop app, no app of the backend (refer to [the architecture of the apps](INDEX.md#the-apps)). Adds to
[SYSTEM.md](../SYSTEM.md). The backups it is for are a proposal, in
[sak-backup/README.md](https://github.com/pellepelster/swiss-army-knife/blob/main/sak-backup/README.md).

## Parts

| Part                  | Where                       | What                                                                       |
|-----------------------|-----------------------------|----------------------------------------------------------------------------|
| sak-backup            | `sak-backup/src-tauri`      | The window: tauri, its commands, its texts (`sak_backup` of texts.yml)     |
| The pages             | `sak-backup/ui`             | The navigation on the left, the menu of the user at the top right          |
| sak-desktop-shared    | `sak-desktop-shared`        | What sak-backup and sakadm share (see below)                               |
| The page of the login | `sak-frontend`, `connect/`  | `/connect-desktop`, where the user confirms the connection of the app      |

## What the desktop apps share

`sak-desktop-shared` is a crate that sak-backup and sakadm use:

- `session`: the sign-in through the browser and the session (`SessionManager`), see below.
- `window`: the tauri commands of the sign-in (`restore_session`, `known_instances`, `login`, `cancel_login`,
  `logout`). An app manages a `SessionManager` (`window::session_manager`) and an `InstanceStore`
  (`window::instance_store`) and lists them in its `generate_handler!`.
- `instances`: the SAK addresses the app signed in at (`InstanceStore`), see below.
- `secrets`: the session tokens in the keychain of the system (`SecretStore`, `Keychain`), see below.
- `i18n`: the language of the system, the texts of the window; its own texts are `desktop_shared` of texts.yml.
- `desktop`: the desktop entry of linux.
- `ui/`: `views.js` (the views and their texts), `login.js` and `login.css` (the view of the sign-in).
  `mise run desktop:ui` copies them to `ui/shared/` of each app, next to the theme in `ui/theme/`.

## Sign-in

The flow is the one of a desktop app in `SECURITY-3`:

1. The user types the SAK address. `localhost:4200`, the address of `local:run`, is `http://localhost:4200`; every
   other address is https.
2. The app reads the api from `<SAK address>/.well-known/sak.json` (`SERVER-6`). An address that names no api fails
   before the browser opens.
3. The app listens on `127.0.0.1` on a random port and opens `/connect-desktop` with the port, a state and the pkce
   challenge. The page signs the user in, the user confirms, the browser comes back to
   `http://127.0.0.1:<port>/callback` with a connect token.
4. The app exchanges the token with the verifier (`POST <api>/auth/login/connect`) and reads the user
   (`GET <api>/auth/who-am-i`).

A sign-in waits 15 minutes for the browser, long enough for a login link by e-mail; Cancel stops it.

## Remembered sign-ins

The session token goes into the keychain of the system after a sign-in: the Secret Service on linux, the Keychain on
macOS, the Credential Manager on windows. The entry is the identifier of the app (`io.pelle.sak.backup`) with the SAK
address as its user, one per instance; the token is never in a file (`SECURITY-3`).

- At the start the app takes the token of the instance used last and asks `who-am-i` with it. When the instance
  accepts it, the app opens signed in; when it refuses it (expired, or the user may no longer sign in) the token is
  deleted and the sign-in says so; when the instance does not answer the token stays and the sign-in says so.
- A sign-out deletes the token. An instance that drops out of `instances.json` loses its token too.
- Without a keychain (no Secret Service running) nothing is kept: the session is in memory only, a line on the
  standard error says why.
- The keychain is reached away from the async threads of the app, as the Secret Service may wait for d-bus.

## Local file

The app remembers the SAK addresses it signed in at, in `instances.json` in its config directory (tauri's
`app_config_dir`, after the identifier `io.pelle.sak.backup`; e.g. `~/.config/io.pelle.sak.backup/` on linux):

```json
{ "instances": [ { "address": "https://sak.example.org/" } ] }
```

- A list, so the app can know several instances later; the one used last is first, the first is the address the
  sign-in shows. At most 20.
- An address goes first after a sign-in that succeeded; a failed one changes nothing, a sign-out keeps it.
- Addresses only, never a session. The file is plain, only for the user (`0600`, its directory `0700`; refer to
  [ACCEPTED-RISKS.md](../../ACCEPTED-RISKS.md)). It is written to a new file that replaces it, so a crash leaves the
  old one or the new one. A file that does not read is no instance, the next sign-in writes it anew.

## Privacy

The app connects to the SAK instance of the user only, and opens the browser of the user. Nothing goes to a third
party.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sak-backup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
