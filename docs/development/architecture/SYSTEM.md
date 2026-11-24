---
title: "System Architecture"
linkTitle: "System"
description: "How SAK is built as a whole: its parts, the backend layers, the api, the model, the external systems, the server of a deployment, the desktop apps, the cloud resources, privacy, security and the tests."
weight: 10
---

# System Architecture

How Swiss Army Knife (SAK) is built as a whole: its parts, how they connect, and the decisions that shape them. This is
the first of three levels: what all components share is in [the architecture of the components](components/INDEX.md),
each component in its own document next to it, e.g. sakadmin and its `SAKADMIN` rules in
[SAKADMIN.md](components/SAKADMIN.md). The rules of the code are here and in those documents; what an agent does before
all of them is in [AGENT.md](../../../AGENT.md). How the apps look and behave is in [UI-UX.md](../ui-ux/UI-UX.md) and the
documents of each kind of app it names, the words in [GLOSSARY.md](../GLOSSARY.md), the weaknesses kept on purpose in
[ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md), what is still to do in [ISSUES.md](../ISSUES.md), and how the dependencies
are updated in [DEPENDENCIES.md](../DEPENDENCIES.md).

A lower level adds to the levels above and never repeats them; a rule two components need moves up to [the architecture
of the components](components/INDEX.md). Every change keeps to its level and to the levels above; a change that has to
break a rule changes its document in the same commit and says why.

Every rule has an id and is written as [RULES.md](../RULES.md) says; code, documents and reviews refer to a rule by
its id.

## Overview

A **deployment** of SAK is one server at a cloud provider (Hetzner Cloud), with a data volume, two primary IP
addresses, a firewall, an ssh key and DNS records. **sakadmin** creates, deploys and destroys deployments. On the
server, the **backend** serves the apps, their api and an S3 api; **caddy** is in front of it.

```text
        user's machine                                    the server of a deployment (podman)
 ┌──────────────────────────┐                ┌───────────────────────────────────────────────────┐
 │ sakadmin (ui and cli)    │──hetzner api──▶│ caddy (sak-ingress)  :443                         │
 │  ├ sak-cloud (resources) │                │   ├ sak.<base domain>  ─▶ backend: sak-frontend   │
 │  └ ansible in a container│──ssh──────────▶│   ├ api.<base domain>  ─▶ backend: api, S3 api    │
 └──────────────────────────┘                │   └ bucket domains     ─▶ the files of a bucket   │
                                             │ sak-backend :8080 ──▶ SQLite, files (data volume) │
                                             │ vector ─▶ victorialogs (the access logs of caddy) │
                                             └───────────────────────────────────────────────────┘
```

## Parts

| Part | What it is |
|---|---|
| `sak-backend` | The server process: the api of the apps, the S3 api, the configuration of caddy, the health checks. It also serves `sak-frontend`. |
| `sak-frontend` | The web app (Angular, installable on a phone). |
| `android/core` | What every Android app shares: the deployment, the sign-in, the session, the api client, the update check, the theme (Kotlin, Jetpack Compose). `android/` is a gradle build of its own. |
| `android/grocery` | The Android app of the grocery lists, on top of `android/core`. |
| `android/notes` | The Android app of the notes, on top of `android/core`. |
| `android/todo` | The Android app of the todo lists, on top of `android/core`. |
| `sak-model` | The database: schema (SQL migrations of Flyway), entities and their repositories (jOOQ on SQLite). |
| `sak-core` | What every part of the backend shares and that is not the api: the texts and their catalogs (`I18N-1`), health, the limits of requests, the mail sender, and how an address is written to the logs. It knows no Ktor. |
| `sak-api` | What every part of the api shares: the error answer, pagination, the language of a request. |
| `sak-auth` | The e-mail login, the session tokens, the signed requests of sakadmin, the roles. |
| `sak-config` | The configuration of the backend and how it is read. |
| `sak-storage` | Binary data on disk (`MODEL-7`): blobs at paths, each written at once; it knows nothing of what it keeps. |
| `sak-s3` | The S3 api of the buckets: its objects kept as blobs in `sak-storage`, below the path the backend gives each bucket. |
| `sak-ingress` | The configuration model of caddy and the client of its admin api. |
| `sak-logs` | The access logs and metrics of the sites, read from victorialogs. |
| `sak-dns` | The check that the domains point to the server, asking name servers directly. |
| `sak-openrouter` | The client of the AI provider (OpenRouter): it reads the text in images and writes down recordings (text discovery). |
| `sak-theme` | The look: design tokens and Bootstrap mapped onto them, used by all uis. |
| `sak-admin-cli` | sakadmin: the sakadmin ui (Tauri) and the sakadmin cli in one binary, and `sak-cloud`, the library that declares the cloud resources. |
| `sak-backup` | sakbackup: the desktop app of the backups, a window (Tauri) and a command line in one binary; it runs restic. |
| `sak-desktop-shared` | What the desktop apps share (`DESKTOP-5`). |
| `sak-i18n` | Writes the texts of `i18n/texts.yml` into the files each part reads (`I18N-1`). |
| `ansible` | The collection `pelle_io.sak` that provisions the server. |
| `sak-e2e` | The end-to-end tests: the apps in a browser, and sakadmin against a real cloud project. |

## Backend layers

- `LAYER-1` **Routes → services → repositories.** All business logic is in a service of its entity, next to the routes
  that use it. A service uses only other services and its own repository. A repository is used only by its service;
  routes use services, never repositories. (RULE-3[OK])

## API

- `API-1` **One form for every error.** Errors answer `{"errors": [{"code": "...", "message": "..."}]}`, for each
  problem its code, the same in every language, and one readable sentence in the language of `Accept-Language`. Inside the backend, an error that can reach a client is always an
  `ErrorMessage` (`sak-api`), a key of a catalog with its values; it becomes an `ApiErrorMessage` only at the api. A
  route answers an error with `call.respondErrors`, and every route has `installApi()` (`sak-api`), which gives an error
  nobody expected the same form.
- `API-2` **Lists are paginated.** A list answers `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked
  for with `offset` and `limit`; `limit` is 50 by default and at most 100 (`DEFAULT_LIMIT`, `MAX_LIMIT` of `sak-api`). A
  service answers a page as `Page<T>`, its items and the total; the route maps the items to their response classes and
  answers them with the `PageRequest` of the call. The exception is the health (`GET /api/v1/health`): the statuses of
  its few checks are one array, without pages.
- `API-3` **Times are ISO 8601 in UTC.** They have milliseconds, always the same form: `2026-10-29T08:00:00.000Z`. That
  holds for every time the api answers (when something was created, updated or computed, when a session ends, the times
  of the logs and the metrics). A time in a response is a `String` written with `Instant.apiTime()` (`sak-api`), never
  with `toString()`. Something that never ends has no time rather than one far away.
- `API-4` **Request and response classes are the boundary.** A domain class never crosses the api, it is mapped to its
  response class. A body the api receives is a `<name>Request`, a body it answers a `<name>Response`, and so is every
  class nested in them (e.g. `PageResponse`). The one exception is `ApiErrorMessage`, an error of an `ApiErrorResponse`.
- `API-6` **Missing entities.** A 404 is only for the resource of the path. An entity the request refers to that is
  not there, e.g. the list of a new item, a photo of a note or the bucket of a redirect, is a 422 with its message.
- `API-7` **Where the code of the api is.** The routes of an app are in `io.pelle.sak.backend.<app>`, its request and
  response classes in `io.pelle.sak.backend.<app>.model`, one class in each file, the file named like the class. The
  other modules that answer on `/api` (`sak-api`, `sak-auth`, `sak-logs`) keep theirs the same way in
  `io.pelle.sak.<module>.model`.
- `API-8` **Verbs and answers.** A create is a `POST` and answers 201 and the new thing. A change is a `PUT`, or a
  `POST` for an action without values (e.g. to check an item), and answers 200 and the thing as it is now. A delete
  is a `DELETE` and answers 204 without a body. A part of a thing has the same name in every path of it (e.g.
  `…/items/{id}/photo`).

## Model

How the backend keeps its data is in [MODEL.md](MODEL.md).

## External systems

- `EXT-1` **One manager for each external system.** A class (a struct in rust) that manages resources outside the
  process, at a cloud provider, in a container runtime or behind a remote api, is called `<name>Manager` after what it
  manages. It is the one place they are looked up, created, changed and deleted, and the one place its clients are made;
  the rest of the code asks it and holds no client of its own. sakadmin has one for the deployments, the provisioning
  and the container runtime (e.g. `DeploymentManager`), the backend one for the AI provider (`OpenRouterManager`) and
  one for the access logs in victorialogs (`AccessLogsManager`). (RULE-3[OK])

## The server of a deployment

- `SERVER-1` **Four containers run as podman quadlets.** Four containers run as podman quadlets, all as the user `sak`:
  the backend, caddy, victorialogs and vector. Ansible pulls their images on each run; a new image restarts its
  container.
- `SERVER-2` **One script for the maintenance.** sakadmin backs up, restores, updates, restarts the services on old
  libraries and reboots the server through ssh with one script, whose commands take turns: a reboot or a restart of the
  deployment stops it in order first and never interrupts a backup.
- `SERVER-3` **All data is on the data volume.** All data is on the data volume, mounted at `/storage/data`, below a
  directory named like the deployment (`/storage/data/<name>`): the database, the files, the buckets, the state of caddy
  and the logs.
- `SERVER-4` **The backend configures caddy at run time.** The backend configures caddy at run time through its admin
  api: the hosts of the apps and the domains of the buckets and the redirects.
- `SERVER-5` **The hosts follow the dns mode.** The hosts depend on the dns mode: the frontend is at `sak.<base
  domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>` or its own domains. The base domain
  follows the dns mode ([GLOSSARY.md](../GLOSSARY.md)).
- `SERVER-6` **The SAK address tells where the api is.** `GET <SAK address>/.well-known/sak.json` answers `{"api":
  "https://api.<base domain>/v1"}`, without a session. A client that knows only the SAK address, the Android apps, finds
  the api there. At any other address than `sak.<base domain>` (the dev server of `local:run`, the backend itself) the
  api is `/api/v1` of the same origin.
- `SERVER-7` **restic is served below the api.** Its REST backend is at `api.<base domain>/restic/`, in front of the S3
  api; `restic` is no name of a bucket.

## Desktop apps

- `DESKTOP-5` **The desktop apps share one crate.** Code that more than one desktop app needs is in
  `sak-desktop-shared`, never copied into an app.
- `DESKTOP-6` **One core, two faces.** A desktop app with a window and a command line does all its work in its core
  (`src/core`). The core knows neither the window (`src/gui`) nor the command line (`src/cli`). Both are thin layers
  on it: they take the input, call the core and show what it answers, and neither does work the other can't do
  through the core.

## Cloud resources

- `CLOUD-1` **The name label.** Every cloud resource of a deployment (server, volume, primary IP, firewall, ssh key,
  DNS record) carries `sak.pelle.io/name=<name>`. It tells the resources of one deployment from those of another and
  from everything else in the project. The firewall selects its servers by this label alone.
- `CLOUD-2` **The version label.** Every resource of a deployment also carries `sak.pelle.io/version=<version>`, the
  version of the sakadmin that created it or changed it last. It is an info label: written with the other labels, never
  compared, so another version alone is no change of a resource. The server gets the version after each provisioning,
  so its label is the version it runs.
- `CLOUD-3` **The password hash label.** Every resource of a deployment, the DNS records included, also carries
  `sak.pelle.io/password-hash=<hash>`, a hash derived from the password: 30 bytes of the master key as hex
  (`keys::password_hash` in `sak-admin-cli`). sakadmin checks a password against it before it changes anything. The
  password itself is nowhere.
- `CLOUD-4` **Two labels are the identity of a resource.** The name label and the password hash label are the identity
  of a resource. Whatever creates a resource sets both labels when it creates it and declares them as its identity
  (`with_identity`, checked by `api::identity`); a resource of SAK without them is a defect. Every lookup checks them: a
  resource of the declared name without them belongs to someone else, and nothing changes or deletes it. A destroy
  deletes only resources with both labels; a resource without the password hash label is never deleted. A registry that
  may destroy declares its owner labels (`Registry::owned_by`).
- `CLOUD-6` **Kinds of labels.** The plan compares the identity labels. It never compares the labels that sakadmin
  keeps on the server itself (the hashes of its ssh keys and user data) or the info labels (the version).
- `CLOUD-7` **Own prefix.** The labels SAK declares are under `sak.pelle.io/`.
- `CLOUD-8` **Deploy and destroy keep the data volume.** A deploy never replaces the data volume or the primary IPs, and
  a destroy keeps the data volume unless the user asks to delete it. Only a resource that may be replaced is
  `Registry::recreatable`; every other resource stays.
- `CLOUD-9` **SAK owns all labels.** sakadmin owns every label of a resource of a deployment, not only the ones under
  its prefix. The plan reports a label it did not declare as a change, and the next deploy removes it, because the
  provider replaces the labels of a resource as a whole. A label added by hand, in the console or by another tool, does
  not survive a deploy.
- `CLOUD-10` **The names of the resources.** Every cloud resource of a deployment is named `sak-<name>`, with a
  suffix for what it is where a deployment has more than one of a kind: the server, the firewall and the ssh key
  `sak-<name>`, the data volume `sak-<name>-data`, the primary ips `sak-<name>-ipv4` and `sak-<name>-ipv6`. A name is
  at most 54 characters, so the longest cloud name stays a dns label of 63. DNS records are named by the dns mode,
  not by this rule.

## Texts

- `I18N-1` **One file of texts.** Every text a user reads, in the backend, the web apps, the android apps and the
  desktop apps, is in `i18n/texts.yml` at the root of the repository, in every language, and only there: no text is
  written into code, a template, a `strings.xml` or a locale file. `mise run i18n:generate` (`sak-i18n`), run after
  every change of the file, writes what each part reads from it; the generated files are committed, never edited, and a
  test fails when they are not up to date. A text of an app is in its section (`<app>.common`, `.backend`, `.web`,
  `.android`), one of no app in `common`, `backend`, `web` or `android`, one of sakadmin in `admin_cli`, one of
  sakbackup in `sak_backup`, one both desktop apps show in `desktop_shared`; a text two parts show alike moves to their
  `common`. (RULE-3[OK])
- `I18N-2` **The desktop texts have three parts.** In `admin_cli`, `cli` has the texts only the sakadmin cli shows, `ui`
  the texts only the sakadmin ui shows, and `common` the texts both show (the messages of the core, the kinds and
  statuses of the resources, the actions of a plan). The code reads them as `cli.*`, `ui.*` and `common.*` from
  `sak-admin-cli/src-tauri/locales/`, which is generated. A text moves to `common` when both faces use it. `sak_backup`
  has the same parts for sakbackup (`sak-backup/src-tauri/locales/`). `desktop_shared` has the texts of
  `sak-desktop-shared`, e.g. the sign-in, which each desktop app shows next to its own. A key is in one of these
  sections only. (RULE-3[OK])

## Privacy

- `PRIVACY-1` **Nothing from outside.** No page loads anything from outside the root domain: no CDN, font service,
  analytics or other third party. Everything is bundled or served by the backend, fonts and libraries included (e.g.
  `@fontsource`, `swagger-ui-dist`). The content security policy (`contentSecurityPolicy`) allows only the page's own
  origin and `api.<base domain>` and is never widened.
- `PRIVACY-2` **Activation and consent first.** Nothing sends data to a third party until it is explicitly activated,
  and the user agrees before each transfer and knows where the data goes. No default activates such a connection.
- `PRIVACY-3` **Exemption: the container registries.** sakadmin and the server pull the images of SAK from the GitHub
  container registry (ghcr.io) without asking: sakadmin the image of its tools and the canary on each start, the server
  the images of the backend and the ingress on each deploy. The server also pulls the two images of its logs
  (VictoriaLogs and Vector) from Docker Hub on each deploy. Each registry then sees the IP address of the machine
  that pulls and which images it pulls, nothing else. Communication with these registries is allowed; SAK cannot run
  without its images.
- `PRIVACY-4` **User data stays.** User data leaves the backend only when the user asks for it (e.g. text discovery),
  and only to providers that do not collect it: a call to OpenRouter sends `provider.data_collection: deny`.

## Security

- `SECURITY-1` **One secret: the password of the deployment.** sakadmin derives one master key from the name and
  password of a deployment with a strong argon2id (256 MiB, 3 passes), the password taken as NFC so the way it was
  typed does not matter. Every value of the deployment is expanded from the master key with a purpose of its own, so
  none gives away another and none can be guessed more cheaply than the master key. The admin password hash is the
  exception: the backend checks a typed password against it, so it is an argon2id of the password itself (64 MiB, 3
  passes, a salt derived from the name):

  | Derived | Used for |
  |---|---|
  | ssh client key | sakadmin's ssh connection to the server |
  | ssh host key | the server's ssh identity, installed by cloud-init, so the first connection is verified |
  | password hash | the `sak.pelle.io/password-hash` label that proves a resource belongs to the deployment |
  | api key | the signatures of sakadmin's requests to the api; the backend has only the public half |
  | backup key | an age key; the server encrypts a backup to its public half, only sakadmin decrypts |
  | admin password hash | the password login of the admin; the backend has only the hash |

  Nothing of it is stored on the user's machine: sakadmin derives it again for every run, so losing a machine loses
  nothing, and anybody with the password has all of it. The password itself is stored nowhere.
- `SECURITY-2` **The ssh key is never written to disk.** The one exception is `sakadmin ssh-config`: it writes the
  key to the directory that the user names. sakadmin never reads the key from a file.
- `SECURITY-3` **A signed in session connects an app.** An app never asks for a password. A signed in session of the web
  app hands it a connect token: good for one exchange within 2 minutes, for a session of the same user with its own full
  time and the roles the user has at the exchange. A phone scans the token as a QR code, which shows only while asked
  for; whoever photographs it signs in as the user until it is used or expired. Before it signs in, the app names the
  SAK instance of the code and asks. A signed request of sakadmin connects no app.
- `SECURITY-4` **A desktop app connects through the browser.** A desktop app gets its connect token (`SECURITY-3`)
  through the browser of the user, after the user confirmed on a page that tells which app asks; the token is of use
  only to the app that asked. A desktop app keeps its session only in the keychain of the system, never in a file.

## Tests

Each test is of one kind, by what it needs to run:

- `TEST-1` **Unit tests run standalone.** They run with no dependencies: nothing but the tools of the build. Mock
  servers and files in the process are allowed. A unit test that needs more is an integration test.
- `TEST-2` **Integration tests use resources of the machine.** They need resources of the machine that runs them:
  Docker, a browser, a specific program such as `ssh-keygen`. A missing resource fails the test, unless the resource is
  optional.
- `TEST-3` **End to end tests need outside resources.** They need resources outside of the machine: cloud servers, a
  cloud project, its API. They can take long and cost money. Images pulled from the GitHub container registry (ghcr.io),
  e.g. those of SAK and its tools, do not count: an integration test may pull them.
- `TEST-4` **Third-party services for tests.** A test may use a third-party service made for tests, e.g. a mail sandbox
  that receives the login links and lets the test read them through its API. It sends only test data: synthetic names
  and addresses, never data of a user.
- `TEST-5` **One task for each kind.** Each part (backend, frontend, cli) has one task for each kind it has. The build
  pipeline runs the unit tests and reports their results; nothing is built into an image before they pass.
