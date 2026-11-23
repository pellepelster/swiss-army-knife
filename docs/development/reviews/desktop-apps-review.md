---
title: "Review: the desktop apps sakadmin and sakbackup"
linkTitle: "Desktop apps"
description: "The review of the two desktop apps (sakadmin, sakbackup) and of what they share (sak-desktop-shared), with their windows, their build and their release, for code that is there twice, for what can be simpler and for general issues."
weight: 12
---

# Review: the desktop apps sakadmin and sakbackup

**Created:** 2026-11-23

Scope: sakadmin (`sak-admin-cli/src-tauri`, `sak-admin-cli/ui`), sakbackup (`sak-backup/src-tauri`, `sak-backup/ui`),
what both share (`sak-desktop-shared`), and what is around them: the tasks of `mise.toml`, the workflow
`swiss-army-knife.yml`, the cargo files and the docs. The questions: what is there twice and belongs in one place;
what can be simpler; what is wrong.

The state reviewed is the working tree of 2026-11-23 with two changes that were not committed yet: sakbackup is
published next to sakadmin (the tasks `backup:build:<platform>`, the workflow job `sakbackup`), and the update of
sakadmin moved to `sak-desktop-shared/src/update.rs` for both apps.

How sure a finding is: **read** (traced in the code by a reviewer, not run), **suspected** (depends on how a
library, the webview or a runner behaves). Nothing was built or started for this review: a finding about the layout
of a window comes from the order of the elements and the stylesheets.

**Checked again:** 2026-11-23, every finding listed is still open. The fixed ones are in the history of git: the
three found while the review ran (the window of sakadmin without the texts of the shared footer, a restart of
sakbackup during a restore or a delete of a snapshot, the footer above the sign-in view), the defects `DA-2` to
`DA-11`, what was there twice, `DA-12` to `DA-18`, the fields of `App` that never differed (`DA-20`) and the tests
that checked little (`DA-25`; sakbackup has a test of its window in chromium now, `SakbackupWindowTest`). Two of them are fixed in part:

- `DA-9`: the token of a repository is still in `RESTIC_REPOSITORY` (suspected to matter only when restic prints its
  address)
- `DA-11`: every build and test runs cargo with `--locked`, but there is no workspace: each app still has a lockfile
  of its own, with versions that differ

What changed was run as the unit tests of the four crates, the tour of the window of sakadmin in chromium
(`WindowVideoTest`) and the start of the window of sakbackup (`SakbackupWindowTest`: the checks, the sign-in, the
footer). The pages of sakbackup behind the sign-in (the confirm dialog, the menus, the password dialog) were read,
not clicked.

What is good: the update is https only, with a list of hosts for the first address and for each redirect, the
sha-256 and then the signature for the tag of the version, a newer version only, and nothing replaced when a step
fails; the sign-in, the session, the keychain and the window commands are shared once, with nothing left in the apps;
`api_message` and `with_causes` have one implementation each; the windows have one static `innerHTML` and the CSP
`default-src 'self'`; no text of a window is written in the code; each `invoke` of both windows names a registered
command, and no registered command is unused; nothing was found that does not compile on macOS or windows; the zips
of windows hold exactly the binaries the update replaces.

## Must decide before sakbackup is released for macOS or windows

### `DA-1` The sign-in through the browser is open to other users of the machine off linux (read)

The page of the sign-in on the loopback takes requests of the own user only on linux
(`sak-desktop-shared/src/session.rs:738-753`). `SEC-38` of the [security review](security-review.md) says that this
is closed, because "sakbackup is built for linux only so far". The workflow job `sakbackup` builds it for macOS and
windows now.

Fix: restrict the page on macOS and windows too, or accept the risk in `ACCEPTED-RISKS.md`. Correct `SEC-38` in both
cases.

## Could fix

### `DA-19` The command lines differ where they need not (read)

- no password and no terminal: sakadmin ends with 2 (`cli/secrets.rs`), sakbackup with 1 (`cli/mod.rs:467-470`)
- `EXIT_UPDATE_AVAILABLE = 7` twice (`sak-admin-cli/src-tauri/src/core/error.rs:26`,
  `sak-backup/src-tauri/src/cli/mod.rs`): next to `update::run` in the shared crate
- sakadmin prints each line with the time and a level and lists its exit codes in `--help`; sakbackup prints plain
  lines and has its exit codes in a comment of the code
- the help of `sakadmin update` says "after checking its sha-256", the one of sakbackup also names the signature
- `sakbackup` answers the update before `command()` and has an `unreachable!` arm for it there
  (`cli/mod.rs:194-221`); sakadmin has the shape without one: a flattened sub-enum and one `match` whose arms answer
  the exit code

### `DA-21` What is still of sakadmin in the shared update (read)

- the test of the trusted root reads `sak-admin-cli/src-tauri/trusted_root.json`; `mise.toml` reads it too. Move the
  file to `sak-desktop-shared/`
- the names of the tests (`an_up_to_date_sakadmin_downloads_nothing`)
- the comment that names `mise run admin-cli:release`

### `DA-22` Random bytes and hex made outside of `encoding.rs` (read)

`sak-desktop-shared/src/session.rs:588-592` (`random_base64`, which reports a failure as "could not listen"),
`sak-backup/src-tauri/src/core/restic.rs:311-313`. `getrandom` in `sak-admin-cli/src-tauri/Cargo.toml` has no use left
in its sources, `hmac` there has none either.

### `DA-23` Names and docs that say sakadmin and mean both (read)

- `admin-cli:release` publishes both apps, `admin-cli:test:unit` and `admin-cli:test:integration` run all four
  crates, `admin-cli:install` installs the tauri command line of both
- `sak-backup/README.md:14-15`: "`sakbackup` is a window for now"
- `components/BACKUP.md:59-60`: "Nothing goes to a third party." An update asks github.com when the user asks;
  BACKUP.md has no rule for the update, `SAKADM-10` names sakadmin only
- `ACCEPTED-RISKS.md:76-77`: the trusted root "is built into sakadmin"
- `supply-chain-review.md:42-44` (`SC-4`): "The three zips"
- `docs/apps/backups.md` sends the reader to the commands of sakadmin to check a signature; they name
  `sak-admin-cli-…zip`. `docs:data` writes the checksums of sakadmin only
- `sak-backup/src-tauri/tauri.conf.json:26-37` configures installers that no task builds

### `DA-24` Small things of the windows (read)

- "Create backup plan" works before the repositories are loaded (`plans.js:62-73`)
- a live region is written on each progress event (`sak-backup/ui/run.js:111-116`,
  `sak-admin-cli/ui/checks.js:74`); the progress bar of sakbackup has no name
- fields checked on submit only (`UX-6`): the numbers a plan keeps (`plans.js:226-245`), the repeated password of
  sakadmin (`create.js:376-384`)
- the markup of the footer of the update is in both `index.html`; `update.js` can make it
- dead code: the page without a page of sakbackup (`app.js:69-75`, `#page-empty`), `#create-smtp-password-hint` of
  sakadmin, `intentText` twice (`create.js:85-87`, `run.js:27-29`), the header with the icon in ten views of sakadmin
- `$`, `invoke` and a builder of elements are written in each module; `views.js` can export them
- `UX-13` wants "Sign out" and "Check for updates" in a menu at the top right; both windows have the update in the
  footer and sakbackup its menu at the bottom of the navigation. Write the desktop apps into `UI-UX.md`, or change
  the windows

## Not reviewed

Most of `core/` of sakadmin (the provisioning, ssh, the keys, the maintenance, the manager of the deployments) and
`sak-admin-cli/cloud`; the integration tests of sakbackup; the german texts; the windows in a running app; whether a
ruleset of the repository names the jobs `sakadmin (…)`; the size of the caches of the workflow.
