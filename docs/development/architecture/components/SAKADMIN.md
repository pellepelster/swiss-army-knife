---
title: "sakadmin"
description: "sakadmin, the desktop app that creates, deploys, backs up and destroys deployments."
weight: 60
---

# sakadmin

No app of the backend: the program on the computer of the user that manages deployments (refer to
[the architecture of the components](INDEX.md#the-components)). Adds to it and to [SYSTEM.md](../SYSTEM.md): the
server it sets up, the cloud resources it declares and what it shares with the other desktop apps are there.

## Purpose

Creates a deployment in the cloud project of the user, deploys changes to it, backs it up, restores it and destroys
it. It is one binary with two faces, the sakadmin ui and the sakadmin cli (`DESKTOP-6`), in `sak-admin-cli`.

## Decisions

- `SAKADMIN-1` **Local state is encrypted.** sakadmin keeps only two things of a deployment in `~/.sak/<name>/`, readable
  by the user only: the settings of the last create or deploy (`config.json`, encrypted with age to a key derived from
  the password) and the backups (`backups/`, `SAKADMIN-6`). The files of a run are deleted when the run ends. All else is
  derived from the name and the password, or read from the deployment: a deploy takes its settings from the running
  deployment, else from `config.json`, else from the DNS records. So two machines with the same password manage the same
  deployment.
- `SAKADMIN-2` **Plan, then apply.** `sak-cloud` declares the resources, compares them with the provider and applies
  them in the order of their dependencies. A plan stops before any change when a resource belongs to someone else or
  must be replaced but must stay. Only the server and its ssh key may be replaced.
- `SAKADMIN-3` **Provisioning runs ansible in the tools image.** Provisioning runs `ansible-playbook` against the server
  in the tools image. The inventory, the keys, the known host key and `backend.env` (admin password hash, addresses,
  mail server) are in a private directory of the run. It sets up the base system, the data volume, the backend and the
  containers next to it.
- `SAKADMIN-4` **The provisioning is a resource.** The roles are a resource of the plan, after the server and the data
  volume. Ansible has no plan, so the server keeps one marker for each role after a successful run: the fingerprint
  of the files of the role and of its values. The plan reads the markers through ssh and runs the playbook when the
  server changes, when markers are missing (a run deletes them first) or when a fingerprint differs. An unchanged
  role runs again only with a forced deploy.
- `SAKADMIN-5` **Caddy gets its certificates by acme.** Caddy gets the certificates from its default acme issuers. A test
  deployment uses the staging ca of Let's Encrypt (a setting of the deployment; sakadmin then trusts its roots), so the
  tests never reach the limits of the production ca.
- `SAKADMIN-6` **Backups through ssh.** The server compresses a backup and encrypts it to the public half of the backup
  key; sakadmin pulls it through ssh into one file. A restore sends it back decrypted. Backups are user data, not
  sakadmin state.
- `SAKADMIN-7` **One version.** sakadmin, its tools image and the server images share the version of the release. A
  server runs the images of the sakadmin that deployed it. Tests can set another tag, e.g. `<version>-rc`.
- `SAKADMIN-8` **Updates on request.** sakadmin looks for a newer release on GitHub and replaces itself only when the
  user asks (sakadmin cli, or the footer of the sakadmin ui). Before that, it checks the download against the checksum
  and the cosign signature of the release workflow at the release tag, offline against the sigstore root built into
  sakadmin.
- `SAKADMIN-9` **Signed images.** The workflow signs each image it pushes with cosign, keyless, as the workflow at its
  ref (a tag for `<version>` and `<version>-rc`, main for `latest`). sakadmin verifies the signature of each image
  digest offline, against the sigstore root built into sakadmin, before it runs the image (canary, tools) or gives it
  to the server by digest (backend, ingress). An image without the signature is not pulled. A tag set with
  `SAK_DOCKER_IMAGE_TAG` that no workflow pushes is pulled unchecked. Third-party images (victorialogs, vector) are
  pinned by digest.

## How it looks and behaves

Adds to the rules of all apps and of the desktop apps in [UI-UX.md](../../UI-UX.md) (`UX-1` to `UX-15`,
`DESKTOP-1` to `DESKTOP-3`).

### Both faces

- `SAKADMIN-10` **A plan that destroys asks first.** Create, deploy and destroy show the plan; when it destroys a resource or
  replaces one, sakadmin asks for a confirmation before it applies: the sakadmin ui in a dialog, the sakadmin cli by
  having the name of the deployment typed. `--yes` skips it in the sakadmin cli; without a terminal and without `--yes`
  the command stops with a usage error and changes nothing.
- `SAKADMIN-11` **No surprise by default.** A deploy keeps the settings of the deployment (e.g. DNS mode, root domain,
  location) unless they are given; a default never changes a running deployment.
- `SAKADMIN-12` **Feature parity.** The sakadmin ui and the sakadmin cli can do the same: what one does, the other
  does too.
- `SAKADMIN-13` **One line per event.** A line has the date, the level and the message, nothing indented. On a terminal the
  level is colored (info green, warning yellow, error red); a warning deserves a second look, e.g. a change the plan
  found or a resource a destroy deletes.

### sakadmin ui

- `SAKADMIN-14` **Plan before change.** The plan (`SAKADMIN-10`) of a deploy is the check on the page of the deployment. Only the
  apply changes something, and it shows each resource as it goes, with the output of the provisioning below.
- `SAKADMIN-15` **A destroy says what goes.** It asks only for the password. The data volume stays (`CLOUD-8`) unless the
  user ticks a box to delete it too; then the form turns red. The plan and a last confirmation dialog say what goes.

### sakadmin cli

- `SAKADMIN-16` **A secret is never an argument.** It would show in the list of processes. Every secret comes from its
  environment variable or is typed on the terminal without echo; its flag (e.g. `--password`) asks even when the
  variable is set. The password of a new deployment is typed twice.
- `SAKADMIN-17` **Test deployments.** `--acme-ca staging` makes a test deployment (`SAKADMIN-5`). The integration tests use it.
- `SAKADMIN-18` **ssh, in the sakadmin cli only.** `sakadmin ssh <name> [-- <command>]` opens a root session or runs a
  command, from the image of the tools: the key derived from the password lives only in an ssh-agent of the container,
  the server is checked against its derived host key.
- `SAKADMIN-19` **Scriptable.** Every input is an argument or an environment variable, `--help` lists them all.
- `SAKADMIN-20` **Exit codes tell what happened.** Success, failure, wrong arguments and each refusal a script may handle
  have a code of their own, listed by `--help`.
