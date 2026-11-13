---
title: "System Architecture"
linkTitle: "System"
description: "How SAK is built as a whole: its parts, the backend layers, the api, the model, the external systems, the server of a deployment, sakctl, the cloud resources, privacy, security and the tests."
weight: 10
---

# System Architecture

How Swiss Army Knife (SAK) is built as a whole: its parts, how they connect, and the decisions that shape them. This
is the first of three levels: what all apps share is in [the architecture of the apps](apps/INDEX.md), each app in its
own document next to it. How to write the code is in [AGENT.md](../../../AGENT.md), how the apps look and behave in
[UI-UX.md](../UI-UX.md), the words in [GLOSSARY.md](../GLOSSARY.md), the weaknesses kept on purpose in
and [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md).

A lower level adds to the levels above and never repeats them; a rule two apps need moves up to [the architecture of
the apps](apps/INDEX.md). Every change keeps to its level and to the levels above; a change that has to break a rule
changes its document in the same commit and says why.

Every rule has an id: the prefix of its chapter and a number, e.g. `MODEL-1`; the rules of an app have the name of the
app as their prefix, e.g. `NOTES-1`. An id never changes and is never used again, also when its rule goes; a new rule
gets the next number of its chapter. Code, documents and reviews refer to a rule by its id.

## Overview

A **deployment** of SAK is one server at a cloud provider (Hetzner Cloud), with a data volume, two primary IP
addresses, a firewall, an ssh key and DNS records. **sakctl** creates, deploys and destroys deployments. On the
server, the **backend** serves the apps, their api and an S3 api; **caddy** is in front of it.

```text
        user's machine                                    the server of a deployment (podman)
 ┌──────────────────────────┐                ┌───────────────────────────────────────────────────┐
 │ sakctl (ui and cli)      │──hetzner api──▶│ caddy (sak-ingress)  :443                         │
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
| `sak-frontend` | The web ui of the apps (Angular, installable on a phone). |
| `android/core` | What every Android app shares: the deployment, the sign-in, the session, the api client, the update check, the theme (Kotlin, Jetpack Compose). `android/` is a gradle build of its own. |
| `android/grocery` | The Android app of the grocery lists, on top of `android/core`. |
| `android/notes` | The Android app of the notes, on top of `android/core`. |
| `sak-model` | The database: schema (Liquibase changelog), entities and their repositories (jOOQ on SQLite). |
| `sak-api` | What every part of the api shares: the error answer, pagination, health. |
| `sak-auth` | The e-mail login, the session tokens, the signed requests of sakctl, the roles. |
| `sak-config` | The configuration of the backend and how it is read. |
| `sak-storage` | Binary data on disk (`MODEL-7`): blobs at paths, each written at once; it knows nothing of what it keeps. |
| `sak-s3` | The S3 api of the buckets: where a bucket is on disk, its objects kept as blobs in `sak-storage`. |
| `sak-ingress` | The configuration model of caddy and the client of its admin api. |
| `sak-logs` | The access logs and metrics of the sites, read from victorialogs. |
| `sak-dns` | The check that the domains point to the server, asking name servers directly. |
| `sak-openrouter` | The client of the AI provider (OpenRouter): it reads the text in images and writes down recordings (text discovery). |
| `sak-theme` | The look: design tokens and Bootstrap mapped onto them, used by all uis. |
| `sak-cli` | sakctl: the sakctl ui (Tauri) and the sakctl cli in one binary, and `sak-cloud`, the library that declares the cloud resources. |
| `ansible` | The collection `pelle_io.sak` that provisions the server. |
| `sak-e2e` | The end-to-end tests: the apps in a browser, and sakctl against a real cloud project. |

### Apps

The backend serves apps to its users, for example static hosting and notes. How they are built is in
[the architecture of the apps](apps/INDEX.md).

## Backend layers

- `LAYER-1` **Routes → services → repositories.** All business logic is in a service of its entity, next to the routes
  that use it. A service uses only other services and its own repository. A repository is used only by its service;
  routes use services, never repositories. Only the repository touches the database (`MODEL-2`).

## API

- `API-1` **Errors** answer `{"errors": [{"code": "...", "message": "..."}]}`, for each problem its code, the same in
  every language, and one readable sentence in the language of `Accept-Language` (`ApiErrorResponse` with its
  `ApiErrorMessage`s). Inside the backend, an error that can reach a client is always an `ErrorMessage` (`sak-api`), a
  key of a catalog with its values; it becomes an `ApiErrorMessage` only at the api.
- `API-2` **Lists** are paginated: `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked for with
  `offset` and `limit`; `limit` is 50 by default and at most 100. The exception is the health (`GET /api/v1/health`):
  the statuses of its few checks are one array, without pages.
- `API-3` **Times** are ISO 8601 in UTC with milliseconds, always the same form: `2026-10-29T08:00:00.000Z`. That
  holds for every time the api answers (when something was created, updated or computed, when a session ends, the
  times of the logs and the metrics). Something that never ends has no time rather than one far away.
- `API-4` **Request and response classes** are the boundary: a domain class never crosses the api, it is mapped to its
  response class.
- `API-6` **Missing entities.** A 404 is only for the resource of the path. An entity the request refers to that is
  not there, e.g. the list of a new item, a photo of a note or the bucket of a redirect, is a 422 with its message.

## Model

- `MODEL-1` **One database.** The backend keeps its data in one SQLite database on the data volume.
- `MODEL-2` **The database is only in `sak-model`.** Only the repository of an entity touches the database.
- `MODEL-3` **Soft delete only.** No row is ever deleted for real. A delete marks the row as deleted; every read skips
  such rows. A value that must be unique is unique among the live rows only. A deleted bucket keeps its name taken
  until it is purged.
- `MODEL-4` **Every row records its time.** When it was created, last updated and deleted, set by the repository from
  one clock.
- `MODEL-5` **Every row records its owner.** The column `owner` holds who created the row: the e-mail address of the
  user, `admin` for the admin of the password login without an address, or `system` when the backend created it on its
  own, for example a setting it generates. A request of sakctl acts as the admin of the password login, so what it
  creates is theirs. The owner is set once, when the row is inserted, and never changes.
- `MODEL-6` **Access is checked in the repository.** A repository method on the data of a user takes the user, and
  the check of who sees or changes the row is in its sql, so a caller can not forget it and no other change comes
  between the check and the read or the change. The service checks the values, never the access. The exceptions are
  the data of the whole deployment, which only the role `admin` reaches (the buckets, the redirects, the settings),
  and the work the backend does on its own (e.g. the text found in a file).
- `MODEL-7` **Binary data only through `FilesystemBinaryStorage`.** All binary data the backend stores (the files of the
  file storage, their thumbnails, the objects of the buckets) is written and read through `FilesystemBinaryStorage`
  (`sak-storage`); no other code writes binary data to disk. The one exception: caddy serves the objects of a bucket
  read only from their directory on disk, which `sak-s3` hands out (`objectsDirectory`).

## External systems

- `EXT-1` **One manager for each external system.** A class that manages resources outside the process, at a cloud
  provider, in a container runtime or behind a remote api, is the one place they are looked up, created, changed and
  deleted, and the one place its clients are made; the rest of the code asks it. sakctl has one for the deployments,
  the provisioning and the container runtime (e.g. `DeploymentManager`), the backend one for the AI provider
  (`OpenRouterManager`) and one for the access logs in victorialogs (`AccessLogsManager`).

## The server of a deployment

- `SERVER-1` Four containers run as podman quadlets, all as the user `sak`: the backend, caddy, victorialogs and
  vector. Ansible pulls their images on each run; a new image restarts its container.
- `SERVER-2` **One script for the maintenance.** sakctl backs up, restores, updates, restarts the services on old
  libraries and reboots the server through ssh with one script, whose commands take turns: a reboot or a restart of the
  deployment stops it in order first and never interrupts a backup.
- `SERVER-3` All data is on the data volume, mounted at `/storage/data`, below a directory named like the deployment
  (`/storage/data/<name>`): the database, the files, the buckets, the state of caddy and the logs.
- `SERVER-4` The backend configures caddy at run time through its admin api: the hosts of the apps and the domains of
  the buckets and the redirects.
- `SERVER-5` The hosts depend on the **dns mode**: the frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>` or its own domains. The
  base domain is the root domain (`domain`) or `<name>.<root domain>` (`subdomain`).
- `SERVER-6` The **SAK address** tells where its api is: `GET <SAK address>/.well-known/sak.json` answers
  `{"api": "https://api.<base domain>/v1"}`, without a session. A client that knows only the SAK address, the Android
  apps, finds the api there. At any other address than `sak.<base domain>` (the dev server of `local:run`, the
  backend itself) the api is `/api/v1` of the same origin.

## sakctl

- `SAKCTL-1` **One core, two faces.** The core (start checks, deployments and plans, provisioning, keys, providers)
  knows neither the sakctl ui nor the sakctl cli. Both are thin layers on it.
- `SAKCTL-2` **Local state is encrypted.** sakctl keeps only two things of a deployment in `~/.sak/<name>/`, readable by
  the user only: the settings of the last create or deploy (`config.json`, encrypted with age to a key derived from
  the password) and the backups (`backups/`, `SAKCTL-8`). The files of a run are deleted when the run ends. All else
  is derived from the name and the password, or read from the deployment: a deploy takes its settings from the running
  deployment, else from `config.json`, else from the DNS records. So two machines with the same password manage the
  same deployment.
- `SAKCTL-3` **Derived keys.** The keys of a deployment are derived from its password ([Security](#security)).
- `SAKCTL-4` **Plan, then apply.** `sak-cloud` declares the resources, compares them with the provider and applies
  them in the order of their dependencies. A plan stops before any change when a resource belongs to someone else or
  must be replaced but must stay. Only the server and its ssh key may be replaced.
- `SAKCTL-5` **Provisioning** runs `ansible-playbook` against the server in the tools image. The inventory, the keys,
  the known host key and `backend.env` (admin password hash, addresses, mail server) are in a private directory of the
  run. It sets up the base system, the data volume, the backend and the containers next to it.
- `SAKCTL-6` **The provisioning is a resource.** The roles are a resource of the plan, after the server and the data
  volume. Ansible has no plan, so the server keeps one marker for each role after a successful run: the fingerprint
  of the files of the role and of its values. The plan reads the markers through ssh and runs the playbook when the
  server changes, when markers are missing (a run deletes them first) or when a fingerprint differs. An unchanged
  role runs again only with a forced deploy.
- `SAKCTL-7` **Certificates.** Caddy gets the certificates from its default acme issuers. A test deployment uses the
  staging ca of Let's Encrypt (a setting of the deployment; sakctl then trusts its roots), so the tests never reach
  the limits of the production ca.
- `SAKCTL-8` **Backups through ssh.** The server compresses a backup and encrypts it to the public half of the backup
  key; sakctl pulls it through ssh into one file. A restore sends it back decrypted. Backups are user data, not
  sakctl state.
- `SAKCTL-9` **One version.** sakctl, its tools image and the server images share the version of the release. A
  server runs the images of the sakctl that deployed it. Tests can set another tag, e.g. `<version>-rc`.
- `SAKCTL-10` **Updates on request.** sakctl looks for a newer release on GitHub and replaces itself only when the user
  asks (sakctl cli, or the footer of the sakctl ui). Before that, it checks the download against the checksum and the
  cosign signature of the release workflow at the release tag, offline against the sigstore root built into sakctl.
- `SAKCTL-11` **Done when it answers.** A create or deploy ends when `api.<base domain>` answers through the ingress
  with its certificate, as users reach it. If it does not answer within some minutes, the run fails with the cause.
- `SAKCTL-12` **Signed images.** The workflow signs each image it pushes with cosign, keyless, as the workflow at its
  ref (a tag for `<version>` and `<version>-rc`, main for `latest`). sakctl verifies the signature of each image
  digest offline, against the sigstore root built into sakctl, before it runs the image (canary, tools) or gives it
  to the server by digest (backend, ingress). An image without the signature is not pulled. A tag set with
  `SAK_DOCKER_IMAGE_TAG` that no workflow pushes is pulled unchecked. Third-party images (victorialogs, vector) are
  pinned by digest.

## Cloud resources

- `CLOUD-1` **The name label.** Every cloud resource of a deployment (server, volume, primary IP, firewall, ssh key,
  DNS record) carries `sak.pelle.io/name=<name>`. It tells the resources of one deployment from those of another and
  from everything else in the project.
- `CLOUD-2` **The version label.** Every resource of a deployment also carries `sak.pelle.io/version=<version>`, the
  version of the sakctl that created it or changed it last. It is an info label: written with the other labels, never
  compared, so another version alone is no change of a resource. The server gets the version after each provisioning,
  so its label is the version it runs.
- `CLOUD-3` **The password hash label.** Every resource of a deployment, the DNS records included, also carries
  `sak.pelle.io/password-hash=<hash>`, a hash derived from the password. sakctl checks a password against it before it
  changes anything. The password itself is nowhere.
- `CLOUD-4` **Identity.** The name label and the password hash label are the identity of a resource. Every lookup
  checks them: a resource of the declared name without them belongs to someone else, and nothing changes or deletes
  it. A destroy deletes only resources with both labels; a resource without the password hash label is never deleted.
- `CLOUD-6` **Kinds of labels.** The plan compares the identity labels. It never compares the labels that sakctl
  keeps on the server itself (the hashes of its ssh keys and user data) or the info labels (the version). A label that
  sakctl did not declare is a change (`CLOUD-9`).
- `CLOUD-7` **Own prefix.** The labels SAK declares are under `sak.pelle.io/`.
- `CLOUD-8` **What stays.** A deploy never replaces the data volume or the primary IPs, and a destroy keeps the
  data volume unless the user asks to delete it.
- `CLOUD-9` **SAK owns all labels.** sakctl owns every label of a resource of a deployment, not only the ones under its
  prefix. The plan reports a label it did not declare as a change, and the next deploy removes it, because the
  provider replaces the labels of a resource as a whole. A label added by hand, in the console or by another tool,
  does not survive a deploy.

## Texts

- `I18N-1` **One file of texts.** Every text a user reads, in the backend, the web apps, the android app and sakctl,
  is in `i18n/texts.yml` at the root of the repository, in every language, and only there: no text is written into
  code, a template, a `strings.xml` or a locale file. `mise run i18n:generate` (`sak-i18n`) writes what each part
  reads from it; the generated files are committed, never edited, and a test fails when they are not up to date. A
  text of an app is in its section (`<app>.common`, `.backend`, `.web`, `.android`), one of no app in `common`,
  `backend`, `web` or `android`, one of sakctl in `ctl`; a text two parts show alike moves to their `common`.

## Privacy

- `PRIVACY-1` **Nothing from outside.** No page loads anything from outside the root domain: no CDN, font service,
  analytics or other third party. Everything is bundled or served by the backend. The content security policy allows
  only the page's own origin and `api.<base domain>`.
- `PRIVACY-2` **Activation and consent first.** Nothing sends data to a third party until it is explicitly activated,
  and the user agrees before each transfer and knows where the data goes. No default activates such a connection. How
  the apps activate a connection is in [the architecture of the apps](apps/INDEX.md#privacy-in-the-apps).
- `PRIVACY-3` **Updates on request only.** sakctl asks GitHub for the newest release only when the user asks for an
  update, and names GitHub before it connects. GitHub then sees the IP address of the user. sakctl never asks on its
  own.
- `PRIVACY-4` **Exemption: the container registry.** sakctl and the server pull the images of SAK from the GitHub
  container registry (ghcr.io) without asking: sakctl the image of its tools and the canary on each start, the server
  the images of the backend and the ingress on each deploy. The registry then sees the IP address of the user's
  machine and of the server, and which images they pull, nothing else. Communication with the registry is allowed; SAK
  cannot run without its images.
- `PRIVACY-5` **Exemption: tests.** Tests may use third-party services made for tests (refer to [Tests](#tests)). They
  send only synthetic test data; a deployment of a user never connects to them.
- `PRIVACY-6` **User data stays.** User data leaves the backend only when the user asks for it (for example text
  discovery), and only to providers that do not collect it.
- `PRIVACY-7` **The camera only when the user uses it.** An Android app asks for the camera only when the user scans a
  QR code (`SECURITY-3`) or takes a photo. A QR code is decoded on the phone, its picture kept nowhere and sent nowhere.

## Security

- `SECURITY-1` **One secret to keep: the password of the deployment.** sakctl derives one master key from the name and
  password of a deployment with a strong argon2id (256 MiB, 3 passes), the password taken as NFC so the way it was
  typed does not matter. Every value of the deployment is expanded from the master key with a purpose of its own, so
  none gives away another and none can be guessed more cheaply than the master key. The admin password hash is the
  exception: the backend checks a typed password against it, so it is an argon2id of the password itself (64 MiB, 3
  passes, a salt derived from the name):

  | Derived | Used for |
  |---|---|
  | ssh client key | sakctl's ssh connection to the server |
  | ssh host key | the server's ssh identity, installed by cloud-init, so the first connection is verified |
  | password hash | the `sak.pelle.io/password-hash` label that proves a resource belongs to the deployment |
  | api key | the signatures of sakctl's requests to the api; the backend has only the public half |
  | backup key | an age key; the server encrypts a backup to its public half, only sakctl decrypts |
  | admin password hash | the password login of the admin; the backend has only the hash |

  Nothing of it is stored on the user's machine: sakctl derives it again for every run, so losing a machine loses
  nothing, and anybody with the password has all of it. The password itself is stored nowhere.
- `SECURITY-2` **The ssh key must never be written to disk.**
- `SECURITY-3` **An app is connected with a QR code.** A signed in session of the web app, by e-mail or with the admin
  password, shows a QR code on request: the SAK address with a connect token in the fragment of the link
  (`https://<sak address>/connect#token=…`), which a browser never sends. The token is good for one exchange within 2
  minutes, for a session of the same user with its own full time; the roles are the ones the user has at the exchange.
  Only the app scans it, in the app, never the browser. Whoever photographs the code signs in as the user until it is
  used or expired: it shows only while asked for, a few codes an hour, and the page tells when an app used it. A signed
  request of sakctl shows no code.

## Tests

Each test is of one kind, by what it needs to run:

- `TEST-1` **Unit tests** run standalone, with no dependencies: nothing but the tools of the build. Mock servers and
  files in the process are allowed. A unit test that needs more is an integration test.
- `TEST-2` **Integration tests** need resources of the machine that runs them: Docker, a browser, a specific program
  such as `ssh-keygen`. A missing resource fails the test, unless the resource is optional.
- `TEST-3` **End to end tests** need resources outside of the machine: cloud servers, a cloud project, its API. They
  can take long and cost money.
- `TEST-4` **Exemption: the container registry.** An integration test may pull images from the GitHub container
  registry (ghcr.io), for example the images of SAK and of the tools it runs. Communication with the registry is
  allowed and does not make it an end to end test.
- `TEST-5` **Third-party services for tests.** A test may use a third-party service made for tests, for example a mail
  sandbox that receives the login links and lets the test read them through its API. It sends only test data:
  synthetic names and addresses, never data of a user.

- `TEST-6` **One task for each kind.** Each part (backend, frontend, cli) has one task for each kind it has. The build
  pipeline runs the unit tests and reports their results; nothing is built into an image before they pass.
