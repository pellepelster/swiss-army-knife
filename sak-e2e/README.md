# sak-e2e

End to end tests in Kotlin (JUnit 5, Playwright for Java). They do not run with `gradle test` or
`gradle build`, because they need more than the build has.

## Local tests

The apps in chromium, against the local environment:

```sh
mise run local:run              # ingress, victorialogs, vector, mailpit, the backend and the frontend
mise run test:e2e:local         # in another terminal, once `mise run local:run` lists the addresses
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

The command line of sakctl, against the Hetzner Cloud project of `HCLOUD_TOKEN` (from pass
when not set):

```sh
mise run test:e2e:integration   # builds sakctl, then runs the tests
```

The tests create, provision and destroy real servers, so a run takes long and costs money.

- Before the run, the tests delete every resource and DNS record of the project whose label
  `sak.pelle.io/name` starts with `test-`. Nothing else is changed.
- The deployments are `test-<5 random characters>`. One uses the DNS zone `krawallbude.de` in
  domain mode, one `krawallbu.de` in subdomain mode. A third one must fail on `krawallbude.de` in
  both modes, each with its own exit code. Both zones must exist in the project.
  `SAK_E2E_DOMAIN_ZONE` and `SAK_E2E_SUBDOMAIN_ZONE` select other zones.
- At the end, every deployment is destroyed with `--delete-data`, and no resource of them may remain.
- `SAK_E2E_SAKCTL` selects another sakctl binary than the one of `cargo build`.
- The servers are created in `hel1`, `SAK_E2E_LOCATION` selects another location (a server type can be sold out in
  one for a while). Every deployment gets its certificates from the staging CA of Let's Encrypt (`--acme-ca staging`).
