# Architecture

How SwissArmyKnife (SAK) is built: its parts, how they connect, and the decisions that shape them. How to write the
code is in [AGENT.md](../AGENT.md), how the apps look and behave in [docs/UI-UX.md](UI-UX.md), the words in
[GLOSSARY.md](GLOSSARY.md), and each feature in [docs/fdr/INDEX.md](fdr/INDEX.md).

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
| `sak-auth` | The e-mail login, the session tokens and the admin api token, the roles. |
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
- All data is on the data volume, mounted at `/storage/data`: the database, the files, the buckets, the state of
  caddy and the logs.
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
- **Authentication:** a session token, or the admin api token of sakctl (its sha-256 in the configuration). A
  session comes from the password login of the admin (always there) or from the e-mail login (only in the multi
  user mode, with a mail server); refer to [FDR-005](fdr/FDR-005-login.md). Without a session or the token, only the login and
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
  a private directory for the run. It sets up the base system, the data volume and the controller: the backend, its
  configuration and the containers next to it.
- **Every deploy provisions.** A deploy always runs the whole playbook, so the server is as the deployment declares
  it whatever happened to it. After a provisioning succeeded the server carries a label that stands for the
  configuration it was provisioned with, keyed by the password, so it tells nothing of the configuration; the plan
  tells from it whether the configuration changed. The label goes before a provisioning starts, so one that failed
  shows as missing.

## Cloud resources

- **The name label.** Every cloud resource of a deployment (server, volume, primary IP, firewall, ssh key, DNS
  record) carries `sak.pelle.io/name=<name>`. It tells the resources of one deployment from those of another
  and from everything else in the project.
- **The password hash label.** Every resource of a deployment, the DNS records included, also carries
  `sak.pelle.io/password-hash=<hash>`, the argon2id hash of the password. sakctl checks a password against it before
  it changes anything. The password itself is nowhere.
- **Identity.** The name label and the password hash label are the identity of a resource. Every lookup checks
  them: a resource of the declared name without them belongs to someone else, and nothing changes or removes it. A
  destroy removes only resources with both labels; a resource without the password hash label is never removed.
- **The settings of a deployment** are labels too: `sak.pelle.io/dns-mode` and `sak.pelle.io/root-domain`. A deploy
  reads them back; a change of either is a label change, never a recreate. The dns mode on a DNS record tells which
  deployments use a root domain.
- **Own prefix.** Labels of SAK are under `sak.pelle.io/`, labels of the user never are.
- **What stays.** A deploy never creates the data volume or the primary IPs anew, and a destroy keeps the data volume
  unless the user asks to delete it.

## Privacy

- **Nothing from outside.** No page loads anything from outside the root domain: no CDN, font service, analytics or
  other third party. Everything is bundled or served by the backend. The content security policy allows only the
  page's own origin and `api.<base domain>`.
- **User data stays.** User data leaves the backend only when the user asks for it (for example text discovery), and
  only to providers that do not collect it.

## Security

- **One secret to keep: the password of the deployment.** sakctl derives the keys of a deployment from its name and
  password with argon2id, each with a salt of its own:

  | Derived | Used for |
  |---|---|
  | ssh client key | sakctl's ssh connection to the server |
  | ssh host key | the server's ssh identity, installed by cloud-init, so the first connection is verified |
  | password hash | the `sak.pelle.io/password-hash` label that proves a resource belongs to the deployment |
  | admin api token | sakctl's access to the api; the backend has only its sha-256 |
  | admin password hash | the password login of the admin; the backend has only the hash |

  Nothing of it is stored on the user's machine: sakctl derives it again for every run, so losing a machine loses
  nothing, and anybody with the password has all of it. The password itself is stored nowhere.
- **Secrets that are not derived.** The backend creates some secrets at run time and keeps them in its database:
  the signing key of the login (a setting) and the secret key of each bucket. They are not derived
  from the password, so changing the password does not change them. The user also supplies some credentials: the
  token of the cloud provider, given to sakctl for each run and never stored by it, and the mail server credentials,
  which sakctl writes to the server's environment for the backend.

## Accepted risks

Known weaknesses that are kept on purpose. A change that touches one keeps the trade-off or revisits it here.

- **The database is not encrypted at rest.** All data in it, the secrets included, is plain in the SQLite file on
  the data volume, against the rule to encrypt all data at rest. Accepted until
  [FDR-002](fdr/FDR-002-sqlite-encryption.md) encrypts the whole file; a separate encryption of the secrets
  alone was removed in favor of it.

- **The hashes in labels are shortened.** A label value holds at most 63 characters, so the hashes a server keeps in
  its labels (of its user data and of its ssh keys) are cut to 240 of their 256 bits to fit one label each. Two
  different values with the same shortened hash would hide a change, and a server would not be created anew when
  it should. Accepted: the chance stays far beyond reach (about one in 2^120 for a collision anyone could search
  for), and one label per hash is simpler than splitting it across several. The password hash label is 240 bits
  as well.

- **The running configuration answers the password of the mail server.** An admin, and sakctl with the admin api
  token, can read it from the backend, so a redeploy takes over the whole mail server and nobody types its password
  again. A comfort feature: whoever holds the password of the deployment or an admin session can read the password of
  the mail server, and send mail with it from elsewhere. Accepted: the same people can already read it on the server,
  and the account at the mail server is meant for the login mails of this deployment alone.

- **The login link budget can be used up** ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). The
  login sends at most 60 links an hour altogether, and at most 5 an hour to an address from one client (keyed by
  address and client IP). Someone who knows an allowed address and has 12 client IPs can use up the links of an
  hour, and no allowed address gets a link until the hour is over. Accepted: the global limit keeps the server from
  being used to send mail, a login outage of an hour is the price, and the apps have few allowed addresses whose
  sessions last 30 days.
