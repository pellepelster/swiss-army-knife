---
title: "System Architecture"
linkTitle: "System"
description: "How SAK is built as a whole: its parts, the server of a deployment, sakctl, the cloud resources, privacy, security and the tests."
weight: 10
---

# System Architecture

How Swiss Army Knife (SAK) is built as a whole: its parts, how they connect, and the decisions that shape them. This
is the first of three levels: what all apps share is in [the architecture of the apps](apps/INDEX.md), each app in its
own document next to it. How to write the code is in [AGENT.md](../../../AGENT.md), how the apps look and behave in
[UI-UX.md](../UI-UX.md), the words in [GLOSSARY.md](../GLOSSARY.md), the weaknesses kept on purpose in
[ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md), and each feature in [fdr/INDEX.md](../fdr/INDEX.md).

A lower level adds to the levels above and never repeats them; a rule two apps need moves up to [the architecture of
the apps](apps/INDEX.md). Every change keeps to its level and to the levels above; a change that has to break a rule
changes its document in the same commit and says why.

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

The backend serves apps to its users, for example static hosting and notes. How they are built is in
[the architecture of the apps](apps/INDEX.md).

## The server of a deployment

- Four containers run as podman quadlets, all as the user `sak`: the backend, caddy, victorialogs and vector.
  Ansible pulls their images on each run; a new image restarts its container.
- **One script for the maintenance.** sakctl backs up, restores, updates and reboots the server through ssh with one
  script, whose commands take turns: a reboot stops the deployment in order first and never interrupts a backup.
  Refer to [FDR-008](../fdr/FDR-008-server-maintenance.md).
- All data is on the data volume, mounted at `/storage/data`, below a directory named like the deployment
  (`/storage/data/<name>`): the database, the files, the buckets, the state of caddy and the logs.
- The backend configures caddy at run time through its admin api: the hosts of the apps and the domains of the
  buckets and the redirects.
- The hosts depend on the **dns mode** (refer to [FDR-001](../fdr/FDR-001-dns-mode.md)): the frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>` or its own domains. The
  base domain is the root domain (`domain`) or `<name>.<root domain>` (`subdomain`).

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
  Refer to [FDR-009](../fdr/FDR-009-provisioning-markers.md).
- **Certificates.** Caddy gets the certificates of a deployment from its default acme issuers, or from the staging
  ca of Let's Encrypt for a test deployment (a setting kept as the label `sak.pelle.io/acme-ca`, whose roots sakctl
  then trusts). The integration tests use the staging ca, so they never run into the limits of the production one.
- **Backups through ssh.** A backup is pulled through ssh into one file on the user's machine, compressed and
  encrypted on the server, which has only the public half of the backup key; a restore sends it back decrypted
  through ssh. The files of the backups are data of the user, not state of sakctl. Refer to
  [FDR-007](../fdr/FDR-007-backup.md).
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
- **Activation and consent first.** Nothing sends data to a third party until it is explicitly activated, and the
  user agrees before each transfer and knows where the data goes. No default activates such a connection. How the
  apps activate a connection is in [the architecture of the apps](apps/INDEX.md#privacy-in-the-apps).
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
