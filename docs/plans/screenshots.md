# Plan: Screenshots and Videos of the UIs

**Status:** Done
**Created:** 2026-10-29
**Last reviewed:** 2026-10-29

## Goal

Make screenshots and short videos of both UIs from a script, so they can be made again after every change:

- **the web apps** (`sak-frontend`): the login, the apps, notes, static hosting, redirects;
- **the window of sakctl** (`sak-cli/ui`): the checks, the providers, the deployments, a create from the form to the
  running deployment, a deploy and a destroy.

Nobody clicks by hand. The images do not change from run to run, unless the UI changes.

## Where we are

`mise run docs:screenshots` (`ScreenshotTest` in `sak-e2e`, Playwright for Java) takes 30 screenshots of the web apps
against the environment of `mise run local:run`: each page on a desktop and on a phone, light, the apps also dark. The
web apps have no video yet, and the window of sakctl has neither screenshots nor a video.

## 1. Videos of the web apps

Playwright records a video of each browser context (`setRecordVideoDir`, `setRecordVideoSize`). One more test class in
`sak-e2e`, `WebVideoTest` (tag `video`), walks through the apps in one context:

1. sign in with the e-mail link (the mail in mailpit for a moment), the apps;
2. notes: write a note, change its color, add a photo, record a voice note;
3. static hosting: create a bucket, open it, its credentials and publishing tab;
4. redirects: create a redirect, open it.

- **Pace:** `slowMo` of about 300 ms, and a short pause on each page, so a viewer can follow. Typing is slowed down
  (`pressSequentially` with a delay).
- **Size:** a desktop (1280×800) only.
- **Seed data:** the same as `ScreenshotTest` (shared in one object `LocalData`), so the video and the screenshots show
  the same notes, bucket and redirects.
- **Output:** Playwright writes `.webm`. The mise task converts it with ffmpeg to `build/videos/web.mp4` (H.264,
  for the browser and GitHub) and to a small `web.gif` (about 10 fps, 800 px wide) for the README.

## 2. Screenshots and video of the window of sakctl

The window is plain HTML and JavaScript (`sak-cli/ui`), and talks to Rust only through `window.__TAURI__.core`: 16
commands with `invoke` (`run_checks`, `list_deployments`, `plan_deployment`, `create_deployment`, …) and `Channel` for
the events of the checks and of a create. Playwright shows it in Chromium, with a fake bridge in place of tauri.

### The page

- **No web server.** Playwright answers every request to `http://sakctl.test/` from the files of `sak-cli/ui`
  (`context.route`). `mise run cli:theme` copies sak-theme into `ui/theme/` first, as for the app.
- **The fake bridge.** `context.addInitScript` puts a `window.__TAURI__` in place before the scripts of the page
  run. `invoke(command, args)` answers from a fixture of the command. A `Channel` plays the events of its fixture one
  after the other, with the delays recorded in it, so the checks and a create run like the real ones.
- **The size of the window:** 736×686 (`tauri.conf.json`), light and dark (`colorScheme`, sak-theme follows it).

### The fixtures

The answers must be what Rust really sends, not hand-written JSON that goes out of date. A Rust test in
`src-tauri` (`ui_fixtures`, run by the mise task, not by `cargo test`) writes them to `build/ui-fixtures/`:

- `ui_texts` and `locale` from `locales/en.yml`, the same call the window makes;
- the deployments, the plan and the providers as the real types (`DeploymentResponse`, `PlanResponse`, …),
  serialized with serde from values built in the test: a deployment `demo` that is up to date, one with changes, one
  missing a resource;
- the events of the checks and of a create (`CheckEvent`, `CreateEvent`), with the tasks of the playbook as the real
  run reports them, each with a delay.

A change of a type or a text changes the fixtures with the next run; a field the window reads and the fixture lacks
fails the run.

### What is shown

`WindowScreenshotTest` (tag `screenshots`, with the web screenshots) and `WindowVideoTest` (tag `video`):

1. the checks while they run, and all done;
2. the providers: none yet, adding a token, the list;
3. the deployments with their states;
4. the form of a create (name, location, DNS mode, root domain, the user mode), then the plan with the status of each
   resource;
5. the create: the resources one after the other, the tasks of the playbook, the wait for the deployment, then the
   page of the new deployment;
6. a deploy of a deployment with changes, and a destroy with the danger zone of "delete the data volume".

Screenshots go to `docs/screenshots/window-<step>[-dark].png`. The video (steps 1 to 5) goes to
`build/videos/window.mp4` and `window.gif`.

### Not done: the real app

The real window under `tauri-driver` (WebDriver over WebKitGTK) with Xvfb and `ffmpeg -f x11grab` would show the real
app against a real Hetzner project. It is slower, flaky, costs money for each run and has no video built in. The fake
bridge shows the same page with the same texts; the integration tests already test the real sakctl.

## 3. Tasks and files

- `mise run docs:screenshots`: the web and the window screenshots (needs `local:run` for the web).
- `mise run docs:video`: the tours of the web and of the window, then ffmpeg to `.mp4` and `.gif`.
- Both stay out of `gradle test`, `gradle build` and `e2e:test:local`, by their tags.
- `docs/screenshots/`: committed. `build/videos/`: never committed.
- The README and `docs/UI-UX.md` show a screenshot of each app and the gif of the window.

## Steps

1. `LocalData`: the seed data of `ScreenshotTest`, shared.
2. `WebVideoTest` and `mise run docs:video` with the ffmpeg step: the videos of the web apps.
3. The fixtures test in Rust and its call from the mise tasks.
4. The fake bridge and the routing of `sak-cli/ui` in `sak-e2e`.
5. `WindowScreenshotTest`, then `WindowVideoTest`.
6. The screenshots and the gifs in the README and in `docs/UI-UX.md`.

## Decisions

- The videos are not committed, they are made again with `mise run docs:video`.
- One video of each UI, on a desktop.
- The window shows the create of `example1` on `krawallbu.de` in domain mode.
- The window has no console: the start of the provisioning and the wait for the deployment show in the status line,
  the tasks of the playbook on the row of the provisioning.
