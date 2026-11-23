---
title: "Review: the desktop apps sakadmin and sakbackup"
linkTitle: "Desktop apps"
description: "The review of the two desktop apps (sakadmin, sakbackup) and of what they share (sak-desktop-shared), with their windows, their command lines, their core, their build and their release: their architecture, code that is there twice, what can be simpler and what is wrong."
weight: 12
---

# Review: the desktop apps sakadmin and sakbackup

**Created:** 2026-11-23

Scope: sakadmin (`sak-admin-cli/src-tauri`, `sak-admin-cli/ui`), sakbackup (`sak-backup/src-tauri`, `sak-backup/ui`),
what both share (`sak-desktop-shared`), and what is around them: the tasks of `mise.toml`, the workflow
`swiss-army-knife.yml`, the cargo files and the docs. The questions: what is there twice and belongs in one place;
what can be simpler; what is wrong.

How sure a finding is: **read** (traced in the code by a reviewer, not run), **suspected** (depends on how a
library, the webview or a runner behaves). Nothing was built or started for a review: a finding about the layout of
a window comes from the order of the elements and the stylesheets.

**First pass**, 2026-11-23, the working tree with sakbackup newly published and the update moved to the shared crate.
Fixed since, in the history of git: the three found while it ran (the window of sakadmin without the texts of the
shared footer, a restart of sakbackup during a restore or a delete of a snapshot, the footer above the sign-in view),
the defects `DA-2` to `DA-11`, what was there twice, `DA-12` to `DA-18`, the fields of `App` that never differed
(`DA-20`) and the tests that checked little (`DA-25`). Two of them are fixed in part:

- `DA-9`: the token of a repository is still in `RESTIC_REPOSITORY` (suspected to matter only when restic prints its
  address)
- `DA-11`: every build and test runs cargo with `--locked`, but there is no workspace: each app still has a lockfile
  of its own, with versions that differ

**Second pass**, 2026-11-23, at `c085ee78`: the architecture of both apps, their window, their command line and their
core (most of the core of sakadmin was not reviewed in the first pass), and the shared crate with its boundary to the
apps. Since the first pass came: excludes with presets, the throughput and the file of a run, a tray icon with the
progress, the look of a running plan, the deb, rpm and AppImage of linux with an update by install kind. Its findings
are `DA-26` to `DA-62`. The most serious claims were checked in the code before they were written down (`DA-26`,
`DA-27`, `DA-34`, `DA-36`, `DA-39`, `DA-41`, `DA-42`); the others are as the reviewers read them.

Fixed since the second pass: `DA-1`, the sign-in that other users of the machine could take over off linux. There
the url the browser is started with has a secret of its sign-in now (`START_SECRET` in `session.rs`), and every other
request of the start page is answered 404 and ends nothing; it rests on macOS and windows showing the arguments of a
process to its user and an admin only, which was not tried on either. `DA-26` and `DA-27`: the plan says whether it deletes or
replaces something (`destroys`, computed in core, read by both faces); the window asks after the plan with what goes,
also for a destroy, and every apply of the window names the plan it showed (`apply_checked`), so a project that
changed since is refused (`SakadminPlanTest`). `DA-28`: `stop_runs` counts its stops, and a provisioning that sees a stop
since it was handed to its thread starts no container, also when the stop came before its run was tracked. `DA-29`: closed while a SAK instance changes, the window of sakadmin stays and
asks; a yes stops the work as cancel does and quits. Found on the way: a change of `locales/` alone rebuilt none of the
three desktop crates, so a build kept old texts; their build scripts name `locales` now. `DA-32`: a backup is one run from its
start to its end (`Runs::begin`), so a cancel while the instance is asked or the password looked up stops it before
restic, and one during the expiry kills forget or prune; the snapshot is done then and the expiry says it was
cancelled. `DA-34`: the tray says nothing runs again after a backup, a restore or a delete of a
snapshot, whatever it showed. `DA-35`: closed during a run without a tray, the window asks as the tray's Quit does. Both
were not tried with a tray, the tests of the window have none. `DA-33`: a backup, a restore and a delete of a snapshot are one run at a time
(`Runs::begin`), and each locks its repository for every other sakbackup of the user (a lock file in the config
directory, `try_lock`), so the command line next to the window is refused with its own text; the snapshots page offers
no delete while a run goes on. Left: a restore can not be cancelled, and the app has no single instance (the lock makes
a second window harmless). `DA-43`: a cancel or a sign-out while the token is exchanged ends the sign-in: the
flag is checked after the exchange and after `who_am_i`, and under the lock of the session after the keychain, which
forgets the token again when it was stopped; the window remembers the instance with the api the sign-in returns. `DA-48`:
the backup and the restore of sakadmin are `core::backup::perform_backup` and `perform_restore`; the command line keeps
its lines and its confirmation (checked by the unit tests and the build; the run itself is in the e2e tests on
Hetzner, which did not run).

## What is good

- **One core for both faces.** sakadmin has a test that keeps tauri and clap out of its core; sakbackup's `back_up`,
  `restore` and `snapshots` serve the command line and the window unchanged. The command line of sakadmin plans and
  applies the same `Planned`, and its check numbers keep an old check from being applied after a newer one.
- **The update** is https only, with a list of hosts for the first address and for each redirect, the sha-256 and
  then the signature for the tag of the version, a newer version only, and nothing replaced when a step fails. The
  install kinds are handled cleanly: a package downloads nothing, an AppImage gets its own asset, the zip replaces the
  other binaries first and the running one last; each kind has a test.
- **The sign-in** follows its design: PKCE, a state that is not on the command line, the token moved off the URL, a
  second `/start` ends it, other users refused on linux and kept out by a secret of the start page elsewhere, no
  redirects followed, the token only to the api recorded at the sign-in. The keychain is reached through `spawn_blocking` and never logs the token.
- **Secrets:** `SECURITY-1` and `SECURITY-2` hold (the client key reaches only `ssh-add -` over stdin, nothing derived
  is stored). The run directory of sakadmin has a random name, 0700/0400, and is removed on drop, stop and signal.
  sakbackup hands the token and the password to restic only through its environment, with the competing
  `RESTIC_*_FILE`/`_COMMAND` removed, and its paths and excludes in 0600 files removed on drop.
- **Backups of sakadmin** are written to a `.partial` file, decrypted whole as a check, then renamed; a restore checks
  the whole file before the server changes anything.
- **The windows** have one static `innerHTML` and the CSP `default-src 'self'`; no text is written in the code;
  `command_tests!` ties each `invoke` to a registered command with its arguments, and the window fixtures are built
  from the real types and texts.
- **The release:** artifacts are signed where they are built and verified before the release against the trusted root
  the apps embed; the actions are pinned by SHA; the tools of the AppImage are pinned, also after the patch of their
  magic bytes; cargo runs with `--locked` everywhere.


## Should fix

### sakadmin

#### `DA-30` The window counts changes by `resourceStatus`, which is not what the apply does (read; the cases suspected)

Stale DNS records are planned `UpToDate` with `WillDestroy` (`manager.rs:654-666`); the window counts changes as
`resourceStatus !== 'up_to_date'` (`deployment.js:156-158`, `run.js:74`), so stale records alone show "up to date" and
keep Deploy disabled. Left-out duplicates get `HasChanges` (`manager.rs:668-680`) but no deploy touches them, so the
page reports a change that never goes away.

Fix: `changes` (resources with an action, without `RunsWhenForced`) and `destroys` (`DA-26`) computed in core on
`PlanResponse`; the faces show those.

#### `DA-31` The errors of the provider lose their cause (read)

`failed`, `message` and `cloud_failure` (`manager.rs:250-282`) format with `to_string()`; a request error of the
Hetzner api then prints without its inner cause (DNS, TLS, refused). `config.rs:204` and `images.rs:310` already use
`with_causes`.

Fix: `sak_desktop_shared::errors::with_causes` in the three helpers.

### sakbackup

#### `DA-36` `sakbackup plan set` changes a plan of another computer (read, checked)

`BACKUP-5` is kept by the faces: `plan delete` checks the host (`cli/mod.rs:448-451`), `plan set` sends the plan's host
without a check (`cli/mod.rs:381-425`); the window checks in JS only (`plans.js`), the commands `update_plan` and
`delete_plan` check nothing although the comment of `delete_plan` names `BACKUP-5` (`gui/commands.rs:50-63`).

Fix: the check in `core::api::update_plan` and `delete_plan` (read the plan, refuse with `OtherComputer`), as
`prepare` does for a run (`run.rs:202-207`).

#### `DA-37` "Forget the password" says it is forgotten when the keychain failed (read)

`PasswordStore::delete` only logs its error (`core/passwords.rs:97-102`, `:114-117`), `passwords::forget` always answers
`Ok` (`passwords.rs:139-147`), and the window says it is forgotten (`repositories.js:63-67`); `set` fails the same
silent way when "remember" is ticked (`BACKUP-3`).

Fix: `delete` and `set` answer a `Result`; a failed `forget` is `RunError::Failed`.

#### `DA-38` The snapshots page names a snapshot's plan by its paths, not by its tag (read)

`planOf` (`snapshots.js:192-195`) matches host and paths; core knows the plan of a snapshot by `sak-plan-<id>`
(`restic.rs:105-111`, `restore.rs:52-67`). Two plans with the same paths get the wrong name.

Fix: `plan_id` on the snapshot, parsed in core from the tag with the fallback of `newest_of`; the page looks the plan up
by id.

#### `DA-39` "Restore where the files were" can not work on windows (read, checked)

The target defaults to `/` in the window (`gui/commands.rs:206`) and on the command line (`cli/mod.rs:60`); `restore`
refuses a target that is not absolute (`core/restore.rs:22`), and `/` is not absolute on windows. What restic wants
there was not verified.

Fix: the target an `Option<PathBuf>` in core, `None` for in place, the root of the platform decided there once.

#### `DA-40` restic's error is its last line, which is often a hint (suspected)

`error_of` (`core/restic.rs:217-223`) takes the last line of stderr. For a locked repository restic ends with "the
`unlock` command can be used to remove stale locks", which then reaches the user as the reason and invites unlocking
a live lock (`DA-33` makes that likely).

Fix: the last line starting with `Fatal:` or `unable to`, else the last one; a `Locked` `RunError` with its own text;
a test with restic's output of a lock.

### Shared

#### `DA-41` sakbackup's window shows sakadmin's icon (read, checked)

`desktop:ui` copies `sak-theme/images/sakadmin` into the `theme/images` of both apps (`mise.toml`, `desktop:ui`); the
splash, the header and the shared sign-in of sakbackup load `theme/images/icon.svg`, which is sakadmin's. The window
has neither the new icon of sakbackup nor a reason to get sakadmin's provider logos.

Fix: the loop of `desktop:ui` maps each app to its images (`sak-backup/ui` to `sakbackup`), with an `icon.svg` in
`sak-theme/images/sakbackup/` (or the pages use its `icon.png`).

#### `DA-42` An inherited `$APPIMAGE` makes the update overwrite another program (read, checked)

`Install::of` takes any absolute `$APPIMAGE` as the app's own AppImage (`sak-desktop-shared/src/update.rs:44-55`). The
variable is inherited: a zip binary started from a shell or an IDE that runs from an AppImage, or by the other app's
AppImage, sees that file. The update then downloads the AppImage asset and renames it over that other program
(`update.rs:441-443`), and the desktop entry starts it (`desktop.rs:31-37`).

Fix: an AppImage only when `current_exe()` lies below `$APPDIR`, the mount the runtime sets; a test with `$APPIMAGE` set
and the binary in `~/.local/bin`, expecting `Binary`.

#### `DA-44` A late 401 signs out the new session (suspected)

`api::call` signs out on any 401 (`api.rs:58-61`), whichever session is current, and cancels a sign-in in progress. A
request made with the old token whose 401 arrives after a new sign-in removes the new session and its keychain entry.

Fix: `request()` hands out the generation of the session; `call` signs out only when the 401 is of the current one and
never cancels a sign-in.

## Could fix

### Architecture

#### `DA-45` What belongs to a face is in core (read)

- sakbackup: `core/passwords.rs:110` uses `tauri::async_runtime::spawn_blocking` and prints with `eprintln!`, against
  "nothing here knows tauri" (`core/mod.rs:3-4`); the command line uses tauri's `block_on` (`cli/mod.rs:288`, `:297`).
- sakadmin: core uses `cli.*` texts in code the window runs too (`ssh.rs:260`, `:263`, `:284`; `manager.rs:424`), and
  picks texts for a face in two ways (`i18n::FRONTEND` in `core/i18n.rs:34-46`, `reboot_hint` in `maintenance.rs:208`);
  `ssh_config.rs` is the command line's only.
- shared: tauri and its opener are hard dependencies of the crate (`Cargo.toml:11-12`); the tauri commands sit in the
  same files as the logic (`update.rs` `version`/`check_update`/`install_update`, `checks.rs` `send`/`quit`, all of
  `window.rs`), so the crate's own tests compile webkitgtk, and the crate does not keep `DESK-2` itself.

Fix: tokio instead of tauri's runtime in sakbackup's core, errors returned instead of printed; `common.*` texts and one
hint enum the faces render in sakadmin; a default feature `tauri` in the shared crate gating `window`, the commands of
the update and of the checks, and `ui_text_commands!`.

#### `DA-46` The body of a backup plan is built twice, by the command line and by the window (read)

When to send the excludes (`cli/mod.rs:359-366`, `:399-421`; `plans.js:606-616`), how an old SAK instance shows
(`excludes_message`, `cli/mod.rs:796-810`; `isMalformed`, `plans.js:635-637`), the default keeps (`default_keep`,
`cli/mod.rs:125-127`; `NEW_PLAN_KEEPS`, `plans.js:330`), the lines of the patterns (`Excludes::lines`; `patternLines`)
and adding a preset (`presets::as_excludes`; `addPreset`, which already differ). The limit of 64 KiB and the UTF-8 check
of a file of excludes exist in the window only (`gui/commands.rs:178-189`), not for `--exclude-file`.

Fix: `core::plans` with typed plan values (build and merge with keeps and excludes, `sends_excludes`, reading a file of
excludes) and an `ExcludesUnsupported` error; the window commands take typed values instead of a `Value`.

#### `DA-47` The windows get errors in several shapes (read)

sakbackup: `ApiError`, `RunError` (with `Api` nested) and plain `String` (`pick_paths`, `read_excludes`, `restart`,
`run_checks`), handled by `message()`, `runFailure` and `String(failure)`; a new `RunError` kind has no check on the JS
side, and an `expire_failed` that is a sign-out drops the "backup done" text (`run.js:173-176`). sakadmin:
`SakadminError` serializes to its message only (`error.rs:56-60`), and commands mix it with `String`; the window can not
tell cancelled, changed since the check, `NotOwned` or `RecreateRefused` apart, so it can not offer "check again".

Fix: one error type per window with a code (`{message, code}` for sakadmin), and a test that lists the kinds against
the JS that renders them, as `command_tests!` does for the invokes.

#### `DA-49` The SAK deployment is modelled in the cloud library (read; whether on purpose not verified)

`sak-admin-cli/cloud/src/hetzner/deployment/` (2914 lines) knows the user mode, the acme CA, the DNS mode, conflicts of
the root domain, the label of the password hash, discovery and state; its README calls it a library that reconciles
resources, so the domain is split between it and `core/deployments`.

Fix: move it into sakadmin, or name it as a crate of its own.

#### `DA-50` Half of the shared crate is sakbackup's only (read)

`session`, `window`, `api`, `secrets`, `instances` and the session part of `cli` are used by sakbackup only; sakadmin
compiles keyring with zbus for nothing, and its page gets `login.js` and all `ui.login.*` texts (its command tests list
`login.js` as unused, `sak-admin-cli/src-tauri/src/gui/mod.rs:95`). `Install` lives in `update.rs` but `desktop.rs`
depends on it, and the writing of files of the update is install logic (`update.rs:635-701`).

Fix: a feature `sign-in` that sakbackup enables, and `DESK-1`/the README saying so; `install.rs` with `Install` and the
writing of files.

### Size and duplication

#### `DA-51` Modules that grew too big (read)

- sakadmin `core/deployments/manager.rs` (1206 lines, no tests of its own): queries, checks of a request, the layout of
  the keys and cloud-init, the rows of a plan (the same 10-field `PlannedResource` three times, `:626-680`) and the
  apply. Split into `queries.rs`, `prepare.rs`, `apply.rs`, the rows into `plan.rs`. Also large: `provision.rs` (688
  lines of code), `maintenance.rs` (535), `ssh.rs` (418), `cli/args.rs` (541), `ui/create.js` (545).
- sakbackup `cli/mod.rs` (1078): into `cli/args.rs`, `cli/progress.rs`, `cli/messages.rs`, the plan building to core
  (`DA-46`); `core/run.rs` (876): `Runs` to `core/runs.rs`, `parse` and `Rate` to `core/progress.rs`, the integration
  tests to a file of their own.
- shared `session.rs` (1527): `address.rs`, `loopback.rs` (the callback server, about 250 lines and its tests),
  `session.rs`; `update.rs` (1250): with `install.rs` (`DA-50`) and the commands (`DA-45`) near 450.

#### `DA-52` Small things written twice (read)

- sakadmin: "the first Hetzner token or `no_provider`" three times (`manager.rs:359-364`, `:457-462`, `:985-995`); the
  default location `nbg1` three times (`cli/args.rs:407`, `gui/deployments.rs:208-218`, `input.rs:175`, the command line
  rejects a bad `SAK_LOCATION`, the window takes nbg1); the default sender twice (`create.js:40`, `input.rs:99`);
  `backup_key` and `local_settings_key` line for line (`keys.rs:191-212`).
- sakbackup: the percent of the same progress is floored by the tray (`tray.rs:88`) and rounded by the window and the
  command line (`run.js:137`, `cli/mod.rs:749`), so the tray says 99% where the row says 100%; the whole percent belongs
  in `RunEvent::Progress`.

### Smaller defects

#### `DA-53` sakadmin: blocking work where it holds others up (suspected)

`cancel_apply` and `restart` are synchronous commands and run `docker rm --force` on the main thread
(`provision.rs:556-575`, `gui/deployments.rs:312`); argon2 with 256 MiB runs in async commands, and the password view
starts a new check after each pause of typing, so checks can overlap with 256 MiB each.

Fix: async commands or `spawn_blocking`; one derivation of the same key at a time.

#### `DA-54` sakadmin: running the checks again fails on a known token (suspected)

`gui/checks.rs:42-44` adds the token of `HCLOUD_TOKEN` with `?`, and `ProviderTokens::add` refuses a duplicate: a reload
of the page ends on the splash screen with "duplicate", and only quit is left.

Fix: a duplicate is no error there.

#### `DA-55` sakadmin: secrets left in memory (read; small)

`encode_private_key` (`keys.rs:263-299`) leaves the private key in unwiped buffers, `expand` returns plain arrays, the
secrets of `backup_key` and `local_settings_key` are plain strings, and the password of a `DeploymentRequest` is a
plain `String`. `let _ = delete_settings` hides a failure after a destroy with its data (`manager.rs:740`).

Fix: `Zeroizing` buffers and one `age_identity(name, password, purpose)`; the failure of `delete_settings` reported.

#### `DA-56` sakbackup: smaller things (read; C7 suspected)

- the ids of plans are `u32` in the commands (`gui/commands.rs:52`, `:61`, `:128`, `:208`), `u64` everywhere else
- a sign-out during a run is allowed (`app.js:548-551`); after a sign-in at another instance, `runningPlan()` can mark a
  plan with the same id there as running
- the progress lines of the command line take `COLUMNS`, which shells seldom export, so on a narrow terminal they wrap
  and the rewrite breaks (`cli/mod.rs:703-710`); the width belongs from the terminal (`TIOCGWINSZ`)
- a Ctrl-C of `sakbackup run` and the tray's Quit leave the private file of the paths in `/tmp` (no handler; `exit`
  right after `cancel`)
- `has_tray()` makes a synchronous D-Bus call with a timeout of 500 ms in a window event (`tray.rs:208-234`)
- the check of restic reports its version but asks for no minimum, although `--files-from-verbatim` and the exit code
  3 need 0.17 (`checks.rs:176-183`)
- the command line has no command to forget a kept password; the window has

#### `DA-57` Shared: smaller things (read)

- the staged file of an update has a fixed name (`target.with_extension("update")`) and is written with
  `std::fs::write`, which follows a symlink; a window install and a command line update at the same time write the same
  file (`update.rs:672-689`). Fix: `create_new` with a random suffix, mode 0755 at creation.
- `request()` would send the token to any absolute URL given as a path (`session.rs:472-480`); no caller does today,
  but it is public. Fix: refuse a path that is not relative.
- the `Exec` of the desktop entry does not escape `%` as `%%` (`desktop.rs:73-81`), so a path with `%` breaks the
  launcher.
- dead public api: `SessionManager::with_timeout`, `update::is_release` (tests only), `update::check` and `install`
  (`pub`, used in their file only).

### Tests and plumbing

#### `DA-58` A missing text or a stale copy of the shared UI shows only at runtime (read)

`text(key)` falls back to the key (`ui/views.js:19`); no test checks the keys of `text('…')` and `data-text` against
`ui_texts()`. `ui/shared/` is a copy that `tauri::generate_context!` embeds as it is; a `cargo build` or `cargo test`
without mise takes an old copy without a word.

Fix: `text_tests!` next to `command_tests!`; each app's `build.rs` compares or copies `sak-desktop-shared/ui` and
reruns when it changes.

#### `DA-59` The tests run on linux only, and some never in CI (read)

`test-unit` is the only job that runs the tests of the desktop crates; the `cfg(windows)` and `cfg(macos)` paths
(`App::binaries` with `-cli.exe`, `write_beside` of the other exe, `config_dir`, `platform()`) never run. Neither
`backup:test:window` nor clippy nor fmt run in CI.

Fix: the shared crate's tests in the macOS and windows legs of `desktop-apps`, which build there anyway;
`backup:test:window` in CI; clippy with `-D warnings` in `admin-cli:test:unit`.

#### `DA-60` Gaps of the tests (read)

- sakadmin: the apply has no unit test (`apply_prepared`, `apply_resources`, `destroy`, `after_provisioning`, the order
  of `keep_settings`, `delete_settings`); a cancel during a provisioning (`DA-28`); `maintenance::perform` beyond its
  lines; `gui/checks.rs`, `providers.rs`, `update.rs`. End to end never: maintenance (update, restart, reboot), `ssh`,
  `ssh-config`, a destroy that keeps the data followed by a create of the same name, a cancel. The window tours are one
  happy path on fixtures: no destroy and its dialog, config deploy, maintenance, cancel or error.
- sakbackup: a cancel during prepare or expiry (`DA-32`), a restore or delete next to a backup (`DA-33`), the tray after
  a restore (`DA-34`), `plan set` on another computer (`DA-36`), a failing keychain (`DA-37`), restic's output of a
  lock (`DA-40`); in the window: the password dialog's flows, restore, the snapshots page and its delete, the texts of
  `runFailure`, a plan of another computer read only.
- shared: a sign-in cancelled during the exchange (`DA-43`), a late 401 (`DA-44`), an inherited `$APPIMAGE` (`DA-42`),
  a parallel install (`DA-57`).

#### `DA-61` Fragile spots of the release (read; the first suspected)

- the guard against unpinned tools of the AppImage reads tauri's log for `Downloading https://` (`desktop:build`); a
  change of wording or colour lets a download through. Fix: check the three pinned files again after the build and
  fail on any other file in tauri's cache.
- a tag that is not a plain `x.y.z` (`v1.2.0-rc.1`) still builds, with packages of version 0.1.0 under names that say
  otherwise, and `gh release create` marks nothing as a pre-release, so the update serves it. Fix: on a tag,
  `desktop:build` fails unless the version is plain semver, or the full semver goes to tauri and the release is
  `--prerelease`.
- the linux leg of `desktop-apps` runs on ubuntu-24.04 while the others are on 26.04; presumably the glibc floor of the
  packages, which the workflow does not say, so an update of the runner silently raises the oldest distribution.
- an updated AppImage keeps its file name with the old version; it works, the docs could suggest a name without one.
- `targets: "all"` in `tauri.conf.json` still configures dmg, msi and nsis, which no task builds (from `DA-23`).

#### `DA-62` sakbackup: a race in the test of a cancelled run (suspected)

`a_cancelled_run_leaves_a_repository_that_the_next_run_and_a_check_take` (`core/run.rs:775`) failed once under the load
of the whole suite: `restic check` found a lock 3 s old after the next backup. It passed alone three times and in the
suite again. It failed the same way once more under load after `DA-32`, and passed on the next run. Not traced: the lock
names the process of the second backup, which had ended.

## Earlier findings still open

### `DA-19` The command lines differ where they need not (read)

- no password and no terminal: sakadmin ends with 2 (`cli/secrets.rs:41-42`), sakbackup with 1
- `EXIT_UPDATE_AVAILABLE = 7` twice (`sak-admin-cli/src-tauri/src/core/error.rs:26`,
  `sak-backup/src-tauri/src/cli/mod.rs:74`): next to `update::run` in the shared crate
- sakadmin prints each line with the time and a level and lists its exit codes in `--help`; sakbackup prints plain
  lines and has its exit codes in a comment of the code
- the help of `sakadmin update` says "after checking its sha-256", the one of sakbackup also names the signature
- `sakbackup` answers the update before `command()` and has an `unreachable!` arm for it there; sakadmin has the shape
  without one

Unchanged in the second pass.

### `DA-21` What is still of sakadmin in the shared update (read)

- the trusted root is `sak-admin-cli/src-tauri/trusted_root.json`, read by the test of the update and by `mise.toml`
  (now three places: `desktop:sign`, `admin-cli:release` and another); move it to `sak-desktop-shared/`
- the names of the tests (`an_up_to_date_sakadmin_downloads_nothing`,
  `the_trusted_root_of_the_repository_is_the_one_sakadmin_embeds`), and the new test of the AppImage uses
  `sakadmin.AppImage`
- the comment that names `mise run admin-cli:release`

Wider in the second pass.

### `DA-22` Random bytes and hex made outside of `encoding.rs` (read)

`sak-desktop-shared/src/session.rs:601-605` (`random_base64`, which reports a failure as "could not listen"),
`sak-backup/src-tauri/src/core/restic.rs:339-342` (`private_file`, now for the paths and the excludes). `getrandom`
and `hmac` in `sak-admin-cli/src-tauri/Cargo.toml` have no use in its sources. Unchanged in the second pass.

### `DA-23` Names and docs that say sakadmin and mean both (read)

Fixed since: the README of sakbackup no longer says "a window for now"; `docs:data` writes the checksums of
sakbackup too; the installers of linux that `tauri.conf.json` configures are built. Still open:

- `admin-cli:release` publishes both apps, `admin-cli:test:unit` and `admin-cli:test:integration` run all four
  crates, `admin-cli:install` installs the tauri command line of both
- `sak-backup/README.md:14-15` says the lists "are empty for now"
- `components/BACKUP.md`: "Nothing goes to a third party." An update asks github.com when the user asks; BACKUP.md has
  no rule for the update, `SAKADM-10` names sakadmin only
- `ACCEPTED-RISKS.md`: the trusted root "is built into sakadmin"
- `supply-chain-review.md` (`SC-4`): "The three zips"

### `DA-24` Small things of the windows (read)

- "Create backup plan" works before the repositories are loaded (`plans.js`)
- a live region is written on each progress event (`sak-backup/ui/run.js`, now with the rate and the time left;
  `sak-desktop-shared/ui/checks.js`); the progress bar of sakbackup has no name (`index.html`)
- fields checked on submit only (`UX-6`): the numbers a plan keeps (`plans.js`), the repeated password of sakadmin
  (`create.js`)
- the markup of the footer of the update is in both `index.html`; `update.js` can make it
- dead code: the page without a page of sakbackup, `#create-smtp-password-hint` of sakadmin (`index.html:274`),
  `intentText` twice (`create.js:84`, `run.js:27`), the header with the icon in ten views of sakadmin
- `$`, `invoke` and a builder of elements are written in each module; `views.js` can export them
- `UX-13` wants "Sign out" and "Check for updates" in a menu at the top right; both windows have the update in the
  footer and sakbackup its menu at the bottom of the navigation. Write the desktop apps into `UI-UX.md`, or change
  the windows

Unchanged in the second pass.

## Not reviewed

`sak-admin-cli/cloud` beyond what the core of sakadmin uses (`DA-49`); the german texts; the windows in a running app
(only in chromium on fixtures); whether a ruleset of the repository names the jobs of `desktop-apps`; the size of the
caches of the workflow; whether tauri's `restart()` starts an AppImage again through `$APPIMAGE`; whether the name of
the deb's desktop file matters for the icon of the taskbar on wayland.
