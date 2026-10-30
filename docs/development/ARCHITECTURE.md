---
title: "Architecture"
description: "How SAK is built: its parts, the server of a deployment, the layers, the api, sakctl, the cloud resources, privacy, security, the tests and the accepted risks."
weight: 10
---

# Architecture

How Swiss Army Knife (SAK) is built: its parts, how they connect, and the decisions that shape them. How to write the
code is in [AGENT.md](../../AGENT.md), how the apps look and behave in [UI-UX.md](UI-UX.md), the words in
[GLOSSARY.md](GLOSSARY.md), and each feature in [fdr/INDEX.md](fdr/INDEX.md).

## Overview

A **deployment** of SAK is one server at a cloud provider (Hetzner Cloud), with a data volume, two primary IP
addresses, a firewall, an ssh key and DNS records. **sakctl** creates, deploys and destroys deployments. On the
server, the **backend** serves the apps, their api and an S3 api; **caddy** is in front of it.

```text
        user's machine                                    the server of a deployment (podman)
 ┌──────────────────────────┐                ┌───────────────────────────────────────────────────┐
 │ sakctl (window and CLI)  │──hetzner api──▶│ caddy (sak-ingress)  :443                         │
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
| `sak-model` | The database: schema (Liquibase changelog), entities and their repositories (jOOQ on SQLite). |
| `sak-api` | What every part of the api shares: the error answer, pagination, health. |
| `sak-auth` | The e-mail login, the session tokens, the signed requests of sakctl, the roles. |
| `sak-config` | The configuration of the backend and how it is read. |
| `sak-s3` | The S3 api of the buckets and their storage on the data volume. |
| `sak-ingress` | The configuration model of caddy and the client of its admin api. |
| `sak-logs` | The access logs and metrics of the sites, read from victorialogs. |
| `sak-dns` | The check that the domains point to the server, asking name servers directly. |
| `sak-openrouter` | The client that reads the text in images (text discovery). |
| `sak-theme` | The look: design tokens and Bootstrap mapped onto them, used by all uis. |
| `sak-cli` | sakctl: a window (Tauri) and a command line in one binary, and `sak-cloud`, the library that declares the cloud resources. |
| `ansible` | The collection `pelle_io.sak` that provisions the server. |
| `sak-e2e` | The end-to-end tests: the apps in a browser, and sakctl against a real cloud project. |

### Apps

The backend serves these apps to its users: static hosting (buckets with an S3 api, served at their own domains),
redirects of domains, notes with labels and files (photos, recordings, text discovery), the access logs of the sites,
and the health of the domains.

## The server of a deployment

- Four containers run as podman quadlets, all as the user `sak`: the backend, caddy, victorialogs and vector.
  Ansible pulls their images on each run; a new image restarts its container.
- **One script for the maintenance.** sakctl backs up, restores, updates and reboots the server through ssh with one
  script, whose commands take turns: a reboot stops the deployment in order first and never interrupts a backup.
  Refer to [FDR-008](fdr/FDR-008-server-maintenance.md).
- All data is on the data volume, mounted at `/storage/data`, below a directory named like the deployment
  (`/storage/data/<name>`): the database, the files, the buckets, the state of caddy and the logs.
- The backend configures caddy at run time through its admin api: the hosts of the apps and the domains of the
  buckets and the redirects.
- The hosts depend on the **dns mode** (refer to [FDR-001](fdr/FDR-001-dns-mode.md)): the frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>` or its own domains. The
  base domain is the root domain (`domain`) or `<name>.<root domain>` (`subdomain`).

## Backend layers

- **Routes → services → repositories.** All business logic is in a service of its entity, next to the routes of
  its app. A service uses only other services and its own repository. A repository is used only by its service;
  routes use services, never repositories.
- **The database is only in `sak-model`.** Only the repository of an entity touches the database.

## Persistence

- **Soft delete only.** No row is ever deleted for real. A delete marks the row as deleted; every read skips such rows.
  A value that must be unique is unique among the live rows only. A deleted bucket keeps its name taken until it is
  purged (refer to [FDR-004](fdr/FDR-004-maintenance-cleanup.md), the only planned purge).
- **Every row records its time.** When it was created, last updated and deleted, set by the repository from one
  clock.
- **Secrets stay with what they belong to.** The secret key of a bucket is stored with the bucket, but is never part
  of the bucket that reaches the api. Secrets that belong to no entity, such as the signing key of the login, are
  settings.
- **Settings.** A setting is one value for a name and a scope; the scope is the system or a user. Their one service
  hands the values out as they are; whoever uses a setting that is a secret keeps it from showing. A secret never
  shows in a log line. The values are plain; the whole
  database is to be encrypted ([FDR-002](fdr/FDR-002-sqlite-encryption.md)).

## API

- **Errors** answer `{"errors": [{"message": "..."}]}`, one readable sentence for each problem.
- **Lists** are paginated: `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked for with `offset`
  and `limit`; `limit` is 50 by default and at most 100.
- **Times** are ISO 8601 in UTC with milliseconds, always the same form: `2026-10-29T08:00:00.000Z`. That holds for
  every time the api answers (when something was created, updated or computed, when a session ends, the times of
  the logs and the metrics). Something that never ends has no time rather than one far away.
- **Request and response classes** are the boundary: a domain class never crosses the api, it is mapped to its
  response class.
- **Authentication:** a session token, or a request sakctl signs with the api key of the deployment (its public
  half in the configuration). A signed request holds for the host of the backend, its method and path, a time no more
  than two minutes away and a nonce taken once; it carries no body and no secret, so a request that went to another
  host or is replayed gets nothing. A
  session comes from the password login of the admin (always there) or from the e-mail login (only in the multi
  user mode, with a mail server); refer to [FDR-005](fdr/FDR-005-login.md). Without a session or a signature, only the login and
  `GET /api/v1/info` answer.

## sakctl

- **One core, two faces.** The core (the checks on start, the deployments and their plans, the provisioning, the
  keys, the providers) knows neither the window nor the command line; both are thin layers on it.
- **No local state.** Everything sakctl needs is derived from the name and password of a deployment, or read back
  from the labels of its resources. Two machines with the same password manage the same deployment.
- **Derived keys.** The keys of a deployment are derived from its password (refer to [Security](#security)).
- **Plan, then apply.** `sak-cloud` declares the resources, diffs them against the provider and applies them in the
  order of their dependencies. A plan stops before anything changes when a resource belongs to someone else or would
  have to be created anew though it must stay. Only the server and its ssh key may be created anew.
- **One manager for each kind of external resource** (the deployments, the provisioning, the container runtime): the
  one place they are looked up, created, changed and removed.
- **Provisioning** runs `ansible-playbook` in a container image of the tools against the server, with the inventory,
  the keys, the known host key and `backend.env` (the hash of the admin password, the addresses, the mail server) in
  a private directory for the run. It sets up the base system, the data volume and the backend, its
  configuration and the containers next to it.
- **The provisioning is a resource.** The roles of the playbook on the server are a resource of the plan like the
  others: after the server and the data volume, with a diff and an apply. Ansible has no plan of its own, so the
  server tells what was applied last: a run that succeeded leaves one marker for each role, the fingerprint of the
  files of the role and of the values the run gave it. The plan reads the markers through ssh and runs the playbook
  when the server changes, when there are no markers (never provisioned, or the last run failed: a run removes them
  first) or when a role has another fingerprint. A role that stays as it was runs again only with a forced deploy.
  Refer to [FDR-009](fdr/FDR-009-provisioning-markers.md).
- **Certificates.** Caddy gets the certificates of a deployment from its default acme issuers, or from the staging
  ca of Let's Encrypt for a test deployment (a setting kept as the label `sak.pelle.io/acme-ca`, whose roots sakctl
  then trusts). The integration tests use the staging ca, so they never run into the limits of the production one.
- **Backups through ssh.** A backup is pulled through ssh into one file on the user's machine, compressed and
  encrypted on the server, which has only the public half of the backup key; a restore sends it back decrypted
  through ssh. The files of the backups are data of the user, not state of sakctl. Refer to
  [FDR-007](fdr/FDR-007-backup.md).
- **One version.** sakctl, the image of its tools and the images of the server have the same version, the version
  of the release. A server runs the images of the version of the sakctl that deployed it. For the tests, another
  tag of the images can be set, the images of a release before it is released (`<version>-rc`).
- **Updates on request.** sakctl compares its version with the newest release on GitHub and replaces itself with it
  only when the user asks, on the command line or in the footer of the window. It checks the download against the
  checksum of the release before it replaces itself.
- **Done when it answers.** A create or deploy ends when the deployment answers at its public endpoint
  (`api.<base domain>`, no login needed), through its ingress and with its certificate, the way its users reach it;
  one that does not answer within some minutes fails, with the cause.

## Cloud resources

- **The name label.** Every cloud resource of a deployment (server, volume, primary IP, firewall, ssh key, DNS
  record) carries `sak.pelle.io/name=<name>`. It tells the resources of one deployment from those of another
  and from everything else in the project.
- **The version label.** Every resource of a deployment also carries `sak.pelle.io/version=<version>`, the version of
  the sakctl that created it or changed it last. It is an info label: written with the other labels, never compared,
  so another version alone is no change of a resource. The server gets the version after each provisioning, so its
  label is the version it runs.
- **The password hash label.** Every resource of a deployment, the DNS records included, also carries
  `sak.pelle.io/password-hash=<hash>`, a hash derived from the password. sakctl checks a password against it before
  it changes anything. The password itself is nowhere.
- **Identity.** The name label and the password hash label are the identity of a resource. Every lookup checks
  them: a resource of the declared name without them belongs to someone else, and nothing changes or removes it. A
  destroy removes only resources with both labels; a resource without the password hash label is never removed.
- **The settings of a deployment** are labels too: `sak.pelle.io/dns-mode`, `sak.pelle.io/root-domain` and
  `sak.pelle.io/user-mode`. A deploy reads them back; a change of one is a label change, never a recreate. The dns mode on a DNS record tells which
  deployments use a root domain.
- **Kinds of labels.** The plan compares the identity labels and the settings. It never compares the labels that
  sakctl keeps on the server itself (the hashes of its ssh keys and user data) or the info labels (the version).
- **Own prefix.** Labels of SAK are under `sak.pelle.io/`, labels of the user never are.
- **What stays.** A deploy never creates the data volume or the primary IPs anew, and a destroy keeps the data volume
  unless the user asks to delete it.

## Privacy

- **Nothing from outside.** No page loads anything from outside the root domain: no CDN, font service, analytics or
  other third party. Everything is bundled or served by the backend. The content security policy allows only the
  page's own origin and `api.<base domain>`.
- **Activation and consent first.** Nothing sends data to a third party until it is explicitly activated (for
  example the provider of text discovery in the configuration of the backend), and the user agrees before each
  transfer and knows where the data goes. No default activates such a connection.
- **Updates on request only.** sakctl asks GitHub for the newest release only when the user asks for an update, and
  names GitHub before it connects. GitHub then sees the IP address of the user. sakctl never asks on its own.
- **Exemption: the container registry.** sakctl and the server pull the images of SAK from the GitHub container
  registry (ghcr.io) without asking: sakctl the image of its tools and the canary on each start, the server the
  images of the backend and the ingress on each deploy. The registry then sees the IP address of the user's machine
  and of the server, and which images they pull, nothing else. Communication with the registry is allowed; SAK
  cannot run without its images.
- **Exemption: tests.** Tests may use third-party services made for tests (refer to [Tests](#tests)). They send only
  synthetic test data; a deployment of a user never connects to them.
- **User data stays.** User data leaves the backend only when the user asks for it (for example text discovery), and
  only to providers that do not collect it.

## Security

- **One secret to keep: the password of the deployment.** sakctl derives one master key from the name and password
  of a deployment with a strong argon2id (256 MiB, 3 passes), the password taken as NFC so the way it was typed does
  not matter. Every value of the deployment is expanded from the master key with a purpose of its own, so none gives
  away another and none can be guessed more cheaply than the master key. The admin password hash is the exception:
  the backend checks a typed password against it, so it is an argon2id of the password itself (64 MiB, 3 passes, a
  salt derived from the name):

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
- **The ssh client key is never written.** The provisioning hands the private key to an ssh agent inside the
  container through its standard input; ansible connects through the agent. What the run needs as files (the
  inventory, the ssh configuration, the public keys, the environment of the backend) sits in a directory of the run
  with a random name that only the user can read, below the user's runtime directory where there is one. It goes
  when the run ends, is interrupted (Ctrl+C, a termination) or the window closes; one that a killed sakctl left
  behind goes with the next run.
- **Secrets that are not derived.** The backend creates some secrets at run time and keeps them in its database:
  the signing key of the login (a setting) and the secret key of each bucket. They are not derived
  from the password, so changing the password does not change them. The user also supplies some credentials: the
  token of the cloud provider, given to sakctl for each run and never stored by it, and the mail server credentials,
  which sakctl writes to the server's environment for the backend.

## Tests

Each test is of one kind, by what it needs to run:

- **Unit tests** run standalone, with no dependencies: nothing but the tools of the build. Mock servers and files
  in the process are allowed. A unit test that needs more is an integration test.
- **Integration tests** need resources of the machine that runs them: Docker, a browser, a specific program such
  as `ssh-keygen`. A missing resource fails the test, unless the resource is optional.
- **End to end tests** need resources outside of the machine: cloud servers, a cloud project, its API. They can
  take long and cost money.
- **Exemption: the container registry.** An integration test may pull images from the GitHub container registry
  (ghcr.io), for example the images of SAK and of the tools it runs. Communication with the registry is allowed and
  does not make it an end to end test.
- **Third-party services for tests.** A test may use a third-party service made for tests, for example a mail
  sandbox that receives the login links and lets the test read them through its API. It sends only test data:
  synthetic names and addresses, never data of a user.

Each part (backend, frontend, cli) has one task for each kind it has. The build pipeline runs the unit tests and
reports their results; nothing is built into an image before they pass.

## Accepted risks

Known weaknesses that are kept on purpose. A change that touches one keeps the trade-off or revisits it here.

- **The database is not encrypted at rest.** All data in it, the secrets included, is plain in the SQLite file on
  the data volume, against the rule to encrypt all data at rest. Accepted until
  [FDR-002](fdr/FDR-002-sqlite-encryption.md) encrypts the whole file; a separate encryption of the secrets
  alone was removed in favor of it.

- **The files of a provisioning run hold the password of the mail server.** The environment of the backend, the
  password of the mail server in it, and the token of the events endpoint are plain files on the user's machine
  while the run lasts, against the rule to encrypt all data at rest. Accepted: only the user reads them, they live
  for the minutes of the run in a directory with a random name, and a killed sakctl leaves them only until its next
  run; whoever reads the user's files can read the user's terminal too.

- **The password can be guessed offline.** Whoever reads the cloud project, a read-only token included, sees the
  password hash label and the public keys of a deployment, and can check guesses of the password against them
  without asking anybody; whoever reads the server can do the same with the admin password hash. Accepted: every
  guess costs the strong argon2id of the master key (or of the admin password hash), and a new password that is easy
  to guess is refused (a strength estimate, not only its length), which leaves too many guesses to try. The salt is
  the name of the deployment, not unique across users of the same name: a salt the user would have to keep would
  break "no local state", and the cost of every guess keeps a table for a common name out of reach.

- **The hashes in labels are shortened.** A label value holds at most 63 characters, so the hashes a server keeps in
  its labels (of its user data and of its ssh keys) are cut to 240 of their 256 bits to fit one label each. Two
  different values with the same shortened hash would hide a change, and a server would not be created anew when
  it should. Accepted: the chance stays far beyond reach (about one in 2^120 for a collision anyone could search
  for), and one label per hash is simpler than splitting it across several. The password hash label is 240 bits
  as well.

- **The ssh host key is in the user data of the server.** cloud-init installs the host key derived from the password,
  so the first connection is verified; the cloud provider keeps the user data, and the metadata service of the server
  hands it out for as long as the server lives. A process that reads it could act as the server towards sakctl.
  Accepted: the server reaches the metadata service only as root, every other process, the containers included (they
  run as the user of SAK, on the network of the server), is refused by the firewall of the server; and root on the
  server, or someone with the cloud project, has everything the host key would give anyway.

- **The running configuration answers the password of the mail server.** An admin, and sakctl with a signed
  request, can read it from the backend, so a redeploy takes over the whole mail server and nobody types its password
  again. A comfort feature: whoever holds the password of the deployment or an admin session can read the password of
  the mail server, and send mail with it from elsewhere. Accepted: the same people can already read it on the server,
  and the account at the mail server is meant for the login mails of this deployment alone.

- **A signed request can be relayed while it is fresh.** Whoever controls the DNS zone of the deployment (it is in
  the cloud project) and gets a certificate for `api.<base domain>` can take a request of sakctl and hand it on to the
  backend within its two minutes, once. Accepted: that is one request, the one sakctl meant to make, and nothing that
  lasts; the signature holds for the backend's own host, so forged labels that send sakctl elsewhere get nothing
  usable at all.

- **The login link budget can be used up** ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). The
  login sends at most 60 links an hour altogether, and at most 5 an hour to an address from one client (keyed by
  address and client IP). Someone who knows an allowed address and has 12 client IPs can use up the links of an
  hour, and no allowed address gets a link until the hour is over. Accepted: the global limit keeps the server from
  being used to send mail, a login outage of an hour is the price, and the apps have few allowed addresses whose
  sessions last 30 days.

- **The window gets the passwords of the environment.** When `SAK_PASSWORD` or `SAK_SMTP_PASSWORD` is set, sakctl
  sends them to the page of the window, which fills its password fields with them, so a user who keeps them in the
  environment does not type them again. The secrets cross the ipc into the web view. Accepted: the web view shows
  only the pages of sakctl, its content security policy loads nothing from elsewhere, the page writes every value
  with `textContent`, and the same process holds the secrets anyway; whoever can read the web view can read sakctl.

- **Two runs on one deployment at the same time.** Nothing stops two sakctl, on two machines or the command line
  next to the window, from deploying or destroying the same deployment at once; the two applies can destroy and
  create the server at the same time. On the server, `sak-backend` lets only one backup, restore, update or reboot
  run at a time; the cloud resources have no such lock. Accepted: a deployment has one user, who starts its runs by
  hand; each run plans first and stops at what it does not expect, and the next deploy repairs what two runs left
  half done. A lock label on the resources would stay behind after a run that was killed.

- **The client key goes to the container engine.** sakctl hands the private client key of a deployment to an
  ssh-agent in a container (`docker exec -i … ssh-add -`) and to the container of a provisioning. It is never
  written to disk, but it passes the engine: with `DOCKER_HOST=tcp://…` and no TLS it would cross the network in
  clear, and the agent that holds it would run on that other host. Accepted for now: sakctl runs against the
  engine of the user's own machine, a local socket; whoever points `DOCKER_HOST` at another host trusts that
  host with the same containers anyway. To revisit: refuse a plain tcp engine for ssh and provisioning.

- **An update is not signed.** sakctl checks the zip of an update against the checksum of the same release, which
  proves that the download is complete, not who made it. Whoever can change a release on GitHub can change both.
  Accepted until the releases are signed: the installation of sakctl trusts the same release in the same way.

- **A label deleted while a note is saved.** A note that is saved with a new label creates the label first. When
  the user deletes that label at the same time, for example on another device, the note is saved without it and
  without an error. When the note is deleted at the same time, the new labels stay without a note. Accepted: it
  takes two devices of the same user acting within milliseconds, nothing is lost but the label on the note, and
  the user sees the result at once and can add the label again; a lock across both for every save costs more
  than it gives.
