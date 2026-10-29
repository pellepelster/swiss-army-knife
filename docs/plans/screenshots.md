# Plan: Automated Screenshots and Videos

**Status:** Proposed
**Created:** 2026-10-29

## Goal

Start the UIs, drive them with a script, and produce screenshots and videos that can be generated again at any time
(docs, README, release notes) without clicking by hand.

## Web frontend (`sak-frontend`)

Playwright is already set up. `playwright.config.ts` starts the stack the way it is deployed (mailpit, victorialogs
and vector in docker, the controller from `./gradlew :sak-backend:installDist`), and `e2e/apps.ts` has `signIn()`,
which signs in with the link from mailpit. `./gradlew :sak-frontend:e2eTest` runs all of it. Playwright records
screenshots and video natively, so only these pieces are missing:

1. **A separate config** `playwright.screenshots.config.ts` that reuses the `webServer` setup of the e2e config and
   only adds recording. The normal e2e run stays fast and records nothing:
   ```ts
   use: {
     viewport: { width: 1280, height: 800 },
     video: { mode: 'on', size: { width: 1280, height: 800 } },
     launchOptions: { slowMo: 250 }, // steps a viewer can follow
   },
   testDir: './screenshots',
   ```
2. **A tour spec** `screenshots/tour.spec.ts` that signs in, walks through the apps (index, redirects, static hosting,
   logs, swagger) and calls `page.screenshot({ path: '../docs/screenshots/<page>.png' })` at each step. To keep the
   images the same from run to run:
   - `animations: 'disabled'` on each screenshot
   - `mask: [...]` over timestamps, session addresses and other values that change
   - fixed seed data (buckets, redirects) created through the API before the tour
   - `page.emulateMedia({ colorScheme: 'light' | 'dark' })` to capture both themes
3. **A Gradle task** `screenshots` in `sak-frontend/build.gradle.kts` with the same dependencies as `e2eTest`, running
   `npx playwright test --config playwright.screenshots.config.ts`.
4. **Post-processing:** Playwright writes one `.webm` per test into `test-results/`. An optional step converts them:
   `ffmpeg -i tour.webm -c:v libx264 -pix_fmt yuv420p docs/screenshots/tour.mp4`, or to a gif for the README.

## Desktop UI (`sak-cli/ui`)

The wizard is plain HTML/JS and talks to Rust only through `window.__TAURI__.core.invoke(...)` (`list_deployments`,
`locations`, `deployment_defaults`, `root_domains`, `check_name`, `detected_settings`, `run_checks`, `quit`) and
`Channel` for events.

**Recommended: a fake bridge in Playwright.**

1. Serve `sak-cli/ui` statically. Playwright's `webServer` can run a small static server.
2. `page.addInitScript` installs a fake `window.__TAURI__` before the page scripts run. `invoke` answers each command
   with fixed data, and `Channel` replays a scripted list of events (e.g. the progress of `run_checks`).
3. The same screenshot, video and theme options as the web frontend, with output to `docs/screenshots/cli-*.png`.

This runs headless and in CI and gives screenshots that don't change between runs. It doesn't exercise the Rust
backend, which is fine for documentation.

**Alternative: the real app via `tauri-driver`** (WebDriver over WebKitGTK) with WebdriverIO. This is end to end, but
it is slower, flakier and has no built-in video; that would need `ffmpeg -f x11grab` under Xvfb. Only worth it if the
desktop UI needs real end-to-end tests too.

## Output

- `docs/screenshots/*.png`: committed and referenced from the README and docs
- videos: build output only (`sak-frontend/test-results/`), converted on demand, not committed

## Steps

1. `playwright.screenshots.config.ts` and the shared `webServer` setup in a common module
2. tour spec for the web frontend with seed data and masks
3. `screenshots` Gradle task
4. fake Tauri bridge, static server and tour spec for the desktop UI
5. reference the screenshots from the README
