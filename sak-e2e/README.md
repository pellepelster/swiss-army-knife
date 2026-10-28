# sak-e2e

End to end tests in Kotlin (JUnit 5, Playwright for Java). They do not run with `gradle test` or
`gradle build`, because they need more than the build has.

## Local tests

The apps in chromium, against the local environment:

```sh
mise run local:run              # ingress, victorialogs, vector, mailpit, the backend and the frontend
mise run e2e:test:local         # in another terminal, once `mise run local:run` lists the addresses
```

The login tests read the links from mailpit (http://localhost:8025) and sign in as
`admin@localhost` (config/local-config.yaml). The backend sends 5 links an hour to an address and
keeps that count in memory. A run asks for one link. Restart the backend to run more often.
`SAK_E2E_FRONTEND_URL` and `SAK_E2E_MAILPIT_URL` point the tests elsewhere. Playwright downloads
its chromium on the first run.

## Integration tests

The command line of sakctl, against the Hetzner Cloud project of `HCLOUD_TOKEN` (from pass
when not set):

```sh
mise run e2e:test:integration   # builds sakctl, then runs the tests
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
