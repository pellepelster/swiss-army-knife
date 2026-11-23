# sak-desktop-shared

What the desktop apps of SAK (sakadmin, sakbackup) share: the sign-in through the browser, its window, the update
from the GitHub release, the texts and the desktop entry of linux. The architecture is in
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
- `checks`: the checks at the start of an app (`Check<F>`, `run_all`) for its splash screen and its command line;
  an app has its own checks and what they find. The command `quit` of the splash screen.
- `update`: the update of an app to the newest release on GitHub, for its `App` (name, zip, binaries, version):
  `run` for the command `update` of its command line, the tauri commands `version`, `check_update` and
  `install_update` for the footer of its window. An app manages its `App`, lists the commands in its
  `generate_handler!` and has a command `restart` of its own.
- `sigstore`: who signs what a release has (the workflow of the repository at a tag or a branch), the check of a
  cosign signature against the trusted root of sigstore built into the app, and the http client that follows a
  redirect only to the hosts its caller allows. The update checks the zip of a release with it, sakadmin its images.
- `command_tests`: the test that every `invoke` of the page of an app names a command of its `generate_handler!`
  with the arguments the command takes; an app calls the macro `command_tests!` in its tests.
- `encoding`, `errors`: hex and random bytes, an error with its causes.
- `VERSION`: the version of SAK of the build (`build.rs`).
- `ui/`: the views with their base stylesheet (`views.js`, `views.css`), the view of the sign-in, the footer of the
  update (`update.js`, `update.css`), the confirm dialog (`confirm-dialog.js`), the menu (`menu.js`) and the splash screen with the checks (`checks.js`,
  `checks.css`). `mise run desktop:ui` copies them to `ui/shared/` of each app,
  next to the theme in `ui/theme/`.

## Working on it

- `cargo test` runs its tests.
- `ui/` is copied into `ui/shared/` of each app by `mise run desktop:ui`.
- `locales/` is generated from `desktop_shared` of `i18n/texts.yml` (`mise run i18n:generate`).
