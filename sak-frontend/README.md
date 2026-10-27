# sak-frontend

The apps, each under a path of its own, served by sak-backend at `apps.<root_domain>`; the index
page lists them (`src/app/apps.ts`). Its build is packaged below `frontend/` in a jar the backend
serves, the api is on the same origin under `/api/v1`. The pages use Bootstrap, the console has
its own theme on top of it (see Console).

## E-mail login

Every app needs a session, the apps and their login are only served when `sak.auth` is configured.

1. `/login` posts the address to `POST /api/v1/auth/login`, which always answers `202`; only the
   `sak.auth.admins` get a mail "Sign in to <root_domain>" with
   `<auth.frontend_url>/signin?token=<login token>`
2. the login token is an ES256 jwt valid for 15 minutes, `/signin` exchanges it once at
   `POST /api/v1/auth/exchange` for a session token (valid for 30 days), stores that in local
   storage and continues at the path the login started from
3. the `authInterceptor` sends the session token as bearer token with every `/api/v1` request, a
   `401` ends the session; the `authGuard` sends signed out users to `/login`

The signing key is generated on first start at `<data_dir>/auth/signing-key.pem`.

```yaml
sak:
  auth:
    admins:
      - admin@example.org
    smtp:
      host: smtp.example.org
      port: 587            # default, starttls
      username: $SAK_SMTP_USERNAME
      password: $SAK_SMTP_PASSWORD
      from: login@example.org
      # tls: true          # implicit tls, e.g. port 465
```

## Notes (`/notes`)

A note taking app: the notes are post-its that can be written on in place, colored and labelled,
and filtered by one of their labels. The backend keeps them (`/api/v1/notes`), every user has
their own; the labels are the ones all apps share (`/api/v1/labels`). Changes show at once and are
saved in the background, the text once typing pauses (`NotesStore`). A note can have photos, taken
with the camera of a phone or chosen from the files: they are scaled down to 2048 pixels and
re-encoded as JPEG in the browser (`PhotoScaler`, which drops the exif data with the location),
stored in the backend's file storage (`POST /api/v1/files`); the notes show their thumbnails
(`/api/v1/files/<sha256>/thumbnail`), each leads to the whole photo (`/api/v1/files/<sha256>`). The robot on a photo discovers the text in
it (`POST /api/v1/files/<sha256>/discover/text`, a vision model at OpenRouter) and appends it to
the text of the note.

A note can also have recordings, taken with the microphone in its footer or in the toolbar for a
new note: an audio worklet hands over the samples, which are stored as 16 kHz mono wav
(`AudioRecorder`, at most 5 minutes). A recording shows its waveform (its thumbnail), plays in
place, and its robot writes it down with a speech to text model at OpenRouter. The page works on phones (one column,
larger hit targets) and on desktops (a wall of post-its).

Every app shows its own favicon: a route names it in its data (`data: { favicon }`, the notes
show their app icon), the other pages show the swiss army knife (`FaviconService`).

## Static hosting (`/static-hosting`)

The buckets of the backend's s3 api, from which caddy serves websites at their domains: the list
of buckets with their objects and size, a page to create a bucket with its domains, a bucket's page
with its overview and its configuration (the domains) and credentials (the s3 endpoint and the key
pair that may access this bucket only, shown on request) and publishing (the deployment target of a
Hugo site and the `hugo deploy` and `aws s3 sync` commands with the key pair) in tabs, and a page
to edit its domains.
It takes `/api/v1/static-hosting`, the same buckets the console shows.

## Redirects (`/redirects`)

The domains caddy redirects, to an external domain or to the first domain a bucket is served at:
the list with where each one leads, deleted after a second click, and a page to create one
(`/api/v1/redirects`).

## Console (`/console`)

The requests and logs of every site, with the look of the blcks console: Bootstrap with the blcks
theme (`src/console-theme`, a copy of blcks-theme), the navigation on the left, the personal menu
with the sign out at its bottom. The theme is the `console` stylesheet of the build, not injected
into every page: `ConsoleShell` loads `console.css` while a page of the console shows and keeps it
light, the other apps follow the light or dark mode of the browser. The console is loaded with its
first page, the other apps do not carry its charts.

Caddy tags every access log entry with the site it served (a bucket with domains, `s3`, `apps`,
`redirect` or `unmatched`), vector ships the log to victorialogs (both run next to the ingress,
see the controller ansible role).

- `/console` shows the requests of every site over the last 4 hours and the data in the buckets
- `/console/logs/statistics/:site` shows the requests per status, the transferred bytes and the
  clients of a site over a range (`GET /api/v1/logs/{site}/metrics?range=`, logsql stats queries)
- `/console/logs/live/:site` follows the requests of a site or `all` of them as they arrive
  (`GET /api/v1/logs/{site}/live`, server-sent events from victorialogs' live tailing), with the
  time, site, path and status of each; pause stops following, a search text (`?q=`) stops it and
  searches the stored entries instead (`GET /api/v1/logs/{site}/search?q=`), follow drops the
  search and follows again

## API

`/doc/openapi.json` (and `/swagger/openapi.json`) is the openapi document of `/api/v1`, generated by
ktor from the descriptions of the routes (`describe { }`, ktor-server-routing-openapi). `/swagger/`
is swagger ui (`public/swagger`, swagger-ui-dist from npm and the swagger theme of the blcks
console), served with the apps, so it uses their session for "try it out".

## Installing the notes as an app

The notes can be installed as an app, on Android e.g. into the app drawer: the app list offers
an Install button when the browser allows it, Chrome's menu has "Install app" as well. The
manifest (`public/notes.webmanifest`) opens the app at `/notes`, its scope is the whole frontend
so the login stays in the app. Angular's service worker (`ngsw-config.json`, production builds
only) keeps the app and the thumbnails on the device, it never answers below `/api` with the
app, the notes always come from the backend.

The icons are drawn as svg in `public/` and rendered to png at the sizes the manifest names. The
notes' icon is the `note-sticky` and the hosting's the `cloud` icon of
[Font Awesome Free](https://fontawesome.com) ([CC BY 4.0](https://fontawesome.com/license/free)),
outlined in ink on paper white with the swiss army knife of the favicon, which is our own, in its
corner.

## Development

```shell
mise run run                    # env:run, backend:run and frontend:run until Ctrl-C
mise run env:run                # ingress, victorialogs, vector and mailpit (config/local-compose.yaml)
mise run backend:run            # the backend on :8080, its login links go to mailpit on :8025
mise run frontend:run           # ng serve on http://localhost:4200, proxies /api to the backend
npm test -- --watch=false       # unit tests, also run by ./gradlew check
```

## End-to-end tests

```shell
./gradlew :sak-frontend:e2eTest
```

Runs the Playwright tests in `e2e/` against the installed controller (`e2e/controller-config.yaml`,
port 18080) with a [mailpit](https://mailpit.axllent.org) e-mail sink (smtp on 11025, api and web
ui on 18025), victorialogs (19428) and vector, all in docker with the images and the vector
configuration the controller role deploys. The tests read the login links from mailpit and follow
them in the browser, and write access log entries vector ships to victorialogs. Needs docker.
