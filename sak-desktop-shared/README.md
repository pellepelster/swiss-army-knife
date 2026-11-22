# sak-desktop-shared

What the desktop apps of SAK (sakadmin, sakbackup) share: the sign-in through the browser, its window, the texts and
the desktop entry of linux. The architecture is in
[SYSTEM.md](../docs/development/architecture/SYSTEM.md#desktop-apps) (`DESK-1`).

## Its modules

- `session`: the sign-in through the browser and the session (`SessionManager`).
- `window`: the tauri commands of the sign-in. An app manages a `SessionManager` and an `InstanceStore` and
  lists the commands in its `generate_handler!`.
- `instances`: the SAK addresses of the sign-ins (`InstanceStore`).
- `secrets`: the session tokens in the keychain of the system (`SecretStore`).
- `i18n`: the language of the system and the texts of the window; its own texts are `desktop_shared` of
  texts.yml.
- `desktop`: the desktop entry of linux.
- `ui/`: the views and the view of the sign-in. `mise run desktop:ui` copies them to `ui/shared/` of each app,
  next to the theme in `ui/theme/`.

## Working on it

- `cargo test` runs its tests.
- `ui/` is copied into `ui/shared/` of each app by `mise run desktop:ui`.
- `locales/` is generated from `desktop_shared` of `i18n/texts.yml` (`mise run i18n:generate`).
