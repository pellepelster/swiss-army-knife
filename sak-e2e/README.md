# sak-e2e

End to end tests in Kotlin (JUnit 5, Playwright for Java). They do not run with `gradle test` or
`gradle build`, because they need more than the build has.

## Local tests

The apps in chromium, against the local environment:

```sh
mise run local:run              # ingress, victorialogs, vector, mailpit, the backend and the frontend
mise run frontend:test:local         # in another terminal, once `mise run local:run` lists the addresses
```

The login tests read the links from mailpit (http://localhost:8025) and sign in as
`admin@localhost` (config/local-config.yaml). The backend sends 5 links an hour to an address and
keeps that count in memory. A run asks for one link. Restart the backend to run more often.
`SAK_E2E_FRONTEND_URL` and `SAK_E2E_MAILPIT_URL` point the tests elsewhere. Playwright downloads
its chromium on the first run.

## Screenshots

The screenshots of `docs/development/screenshots/` and the videos of `build/videos/`, taken in chromium. They
do not run with the local tests. The videos are not committed.

```sh
mise run local:run              # in one terminal
mise run docs:screenshots       # in another one: the apps and the window of sakctl
mise run docs:video             # the tours of the apps and of the window, as .webm, .mp4 and .gif
```

The window of sakctl (`sak-cli/ui`) runs without tauri and without a cloud project: Playwright
serves its files, and a fake `window.__TAURI__` answers its commands with what rust sends for a
create of `example1` on `krawallbu.de` in domain mode. A rust test writes these answers from the
real types and texts (`core/deployments/window_fixtures.rs`, `mise run docs:window`). The window
is shown at its own size, 736×686, in light and dark; its video shows the checks, a provider
token, the create of `example1` with the provisioning and the page of the new deployment.

Every page is taken on a desktop (1280×800) and on a phone (390×844), without the browser
around it: the login with the password and by e-mail (the mail in mailpit too), the apps in the
light and the dark theme, the notes app (text notes in each color, a note with a photo and one
with a recording), static hosting and redirects. The run signs in with the local admin password
(`SAK_LOCAL_ADMIN_PASSWORD_HASH` of `mise.toml`) and asks for two login links. It creates its own
notes, a bucket and two redirects; other data of the local environment stays and can show.

## Integration tests

Two deployments in the Hetzner Cloud project of `HCLOUD_TOKEN`, created with sakctl, and the apps in chromium
against them:

```sh
mise run cli:test:e2e   # builds sakctl, then runs the tests
```

Before the tests, the task runs the tests of sak-cloud against the API of Hetzner, and, outside of CI, builds and
pushes all images of SAK as `<VERSION>-rc` (`mise run docker:all:push`, for amd64 and the platform of the machine;
docker must be logged in to ghcr.io). The deployments run these images (`SAK_DOCKER_IMAGE_TAG`, `<VERSION>-rc` unless
set), so they run the roles and the backend of this source; in CI the jobs of the images push them.

The tests create, provision and destroy real servers, so a run takes about 30 minutes and costs money. A server is
expensive, so all tests share two deployments (`util/TestDeployments.kt`):

- `test-<id>-single`: single user, domain mode on `krawallbude.de`.
- `test-<id>-multi`: multi user, subdomain mode on `krawallbu.de`. It sends its login links through the SMTP of a
  Mailtrap sandbox, and the tests read them through the API of Mailtrap. Only synthetic addresses go there.

The first test that needs them creates both at once, with `sakctl create`. When the run ends, also after a failure,
both are destroyed with `sakctl destroy --delete-data`, and no resource of them may remain. Running one test class
creates both too.

The tests run in this order:

1. `LoginTest`: the password on the single user deployment; the e-mailed link (once only) and the password on the
   multi user one.
2. `NotesTest`: the same tests on both deployments; a note is created, kept and deleted.
3. `BackupTest`: on both, a note, `sakctl backup`, the note deleted and another one created, `sakctl restore`: the
   note of the backup is back, the later one is gone.
4. `SakctlTest`: the rules of the command line, without a server of their own. A second domain deployment and the
   mixed DNS modes are refused, with their exit codes; an ssh key of someone else is neither removed nor taken over;
   `config show` shows no secret; a deploy keeps the settings.

Each step and each wait is a line on the output while the tests run, with the time and the deployment it is about.

What the run needs, from pass unless set:

| Variable | pass |
|---|---|
| `HCLOUD_TOKEN` | `hetzner/pelle.io/hcloud_api_token` |
| `MAILTRAP_API_TOKEN` | `mailtrap/pelle/api-token` |
| `SAK_E2E_SMTP_HOST`, `SAK_E2E_SMTP_PORT` | `mailtrap/pelle/smtp-server`, `mailtrap/pelle/smtp-port` (Hetzner blocks 25 and 465) |
| `SAK_E2E_SMTP_USERNAME`, `SAK_E2E_SMTP_PASSWORD` | `mailtrap/pelle/smtp-user`, `mailtrap/pelle/smtp-password` |

- Before the run, the tests delete every resource and DNS record of the project whose label `sak.pelle.io/name`
  starts with `test-`, and empty the sandbox. Nothing else is changed.
- Both zones must exist in the project. `SAK_E2E_DOMAIN_ZONE` and `SAK_E2E_SUBDOMAIN_ZONE` select other zones.
- The browser reaches the new names of a deployment at the address of its server at once (`--host-resolver-rules`),
  whatever the DNS of the machine still remembers. The deployments get their certificates from the staging CA of
  Let's Encrypt (`--acme-ca staging`), which the browser does not check.
- `SAK_E2E_SAKCTL` selects another sakctl binary than the one of `cargo build`.
- `SAK_DOCKER_IMAGE_TAG` selects the tag of the images, `SAK_DOCKER_PLATFORMS` the platforms they are pushed for.
- The servers are created in `hel1`, `SAK_E2E_LOCATION` selects another location (a server type can be sold out in
  one for a while).
- A run sends one mail; the free plan of Mailtrap has 50 a month.
