# sak-console

The Angular console of sak-controller, served at `console.<root_domain>`. Its build is packaged
below `console/` in a jar that sak-controller serves, the api is on the same origin under `/api`.
The console and its login are only served when `sak.auth` is configured.

## E-mail login

1. `/login` posts the address to `POST /api/auth/login`, which always answers `202`; only the
   `sak.auth.allowed_emails` get a mail with `<console>/signin?token=<login token>&redirect=<path>`
2. the login token is an ES256 jwt valid for 15 minutes, `/signin` exchanges it once at
   `POST /api/auth/exchange` for a session token (valid for 30 days), stores that in local
   storage and continues at the path the login started from
3. the `authInterceptor` sends the session token as bearer token with every `/api` request, a
   `401` ends the session; the `authGuard` sends signed out users to `/login`

The signing key is generated on first start at `<data_dir>/auth/signing-key.pem`.

```yaml
sak:
  auth:
    allowed_emails:
      - admin@example.org
    smtp:
      host: smtp.example.org
      port: 587            # default, starttls
      username: $SAK_SMTP_USERNAME
      password: $SAK_SMTP_PASSWORD
      from: login@example.org
      # tls: true          # implicit tls, e.g. port 465
```

## Development

```shell
npm ci
npm start                       # ng serve, proxies /api to a controller on localhost:8080
npm test -- --watch=false       # unit tests, also run by ./gradlew check
```

## End-to-end tests

```shell
./gradlew :sak-console:e2eTest
```

Runs the Playwright tests in `e2e/` against the installed controller (`e2e/controller-config.yaml`,
port 18080) with a [mailpit](https://mailpit.axllent.org) e-mail sink in docker (smtp on 11025,
api and web ui on 18025). The tests read the login links from mailpit and follow them in the
browser. Needs docker.
