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

What the user sees is in [UI-UX.md](../../UI-UX.md): `DESKTOP-1` (a sign-in is remembered), `DESKTOP-2` (sign-in
through the browser) and `DESKTOP-3` (the SAK addresses are remembered). The security of it is `SECURITY-3`. In the
code:

- The api of a SAK address is the one of its `/.well-known/sak.json` (`SERVER-6`). A release takes https only; a
  debug build (`mise run backup:run`) takes http on the loopback too, e.g. `localhost:4200` of `local:run`.
- The browser is started on a page of the app on the loopback, which leads it on to `/connect-desktop` of the
  instance once; the url the browser is started with has no secret. The token comes back to the loopback and is
  bound to the app by pkce. A sign-in waits 15 minutes for the browser.
- The addresses are in `instances.json` in the config directory of the app (`{ "instances": [ { "address": … } ] }`,
  at most 20, only for the user; refer to [ACCEPTED-RISKS.md](../../ACCEPTED-RISKS.md)).
- The session token is in the keychain under the identifier of the app, with the SAK address as its user. The app
  shows one instance, so a sign-in deletes the tokens of the other addresses.

## Privacy

The app connects to the SAK instance of the user only, and opens the browser of the user. Nothing goes to a third
party.

## Run and test

- `mise run backup:run` runs the app with reload.
- `mise run admin-cli:test:unit` runs the tests of sak-desktop-shared and sak-backup too.
- Against `mise run local:run`: sign in at `localhost:4200` as `admin@localhost`; the login link arrives in mailpit
  (`http://localhost:8025`).
