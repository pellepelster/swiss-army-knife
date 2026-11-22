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
it. It is one binary with two faces, the sakadmin ui and the sakadmin cli (`DESK-2`), in `sak-admin-cli`.

## Decisions

- `SAKADM-2` **Local state is encrypted.** sakadmin keeps only two things of a deployment in `~/.sak/<name>/`, readable
  by the user only: the settings of the last create or deploy (`config.json`, encrypted with age to a key derived from
  the password) and the backups (`backups/`, `SAKADM-8`). The files of a run are deleted when the run ends. All else is
  derived from the name and the password, or read from the deployment: a deploy takes its settings from the running
  deployment, else from `config.json`, else from the DNS records. So two machines with the same password manage the same
  deployment.
- `SAKADM-4` **Plan, then apply.** `sak-cloud` declares the resources, compares them with the provider and applies
  them in the order of their dependencies. A plan stops before any change when a resource belongs to someone else or
  must be replaced but must stay. Only the server and its ssh key may be replaced.
- `SAKADM-5` **Provisioning** runs `ansible-playbook` against the server in the tools image. The inventory, the keys,
  the known host key and `backend.env` (admin password hash, addresses, mail server) are in a private directory of the
  run. It sets up the base system, the data volume, the backend and the containers next to it.
- `SAKADM-6` **The provisioning is a resource.** The roles are a resource of the plan, after the server and the data
  volume. Ansible has no plan, so the server keeps one marker for each role after a successful run: the fingerprint
  of the files of the role and of its values. The plan reads the markers through ssh and runs the playbook when the
  server changes, when markers are missing (a run deletes them first) or when a fingerprint differs. An unchanged
  role runs again only with a forced deploy.
- `SAKADM-7` **Certificates.** Caddy gets the certificates from its default acme issuers. A test deployment uses the
  staging ca of Let's Encrypt (a setting of the deployment; sakadmin then trusts its roots), so the tests never reach
  the limits of the production ca.
- `SAKADM-8` **Backups through ssh.** The server compresses a backup and encrypts it to the public half of the backup
  key; sakadmin pulls it through ssh into one file. A restore sends it back decrypted. Backups are user data, not
  sakadmin state.
- `SAKADM-9` **One version.** sakadmin, its tools image and the server images share the version of the release. A
  server runs the images of the sakadmin that deployed it. Tests can set another tag, e.g. `<version>-rc`.
- `SAKADM-10` **Updates on request.** sakadmin looks for a newer release on GitHub and replaces itself only when the
  user asks (sakadmin cli, or the footer of the sakadmin ui). Before that, it checks the download against the checksum
  and the cosign signature of the release workflow at the release tag, offline against the sigstore root built into
  sakadmin.
- `SAKADM-12` **Signed images.** The workflow signs each image it pushes with cosign, keyless, as the workflow at its
  ref (a tag for `<version>` and `<version>-rc`, main for `latest`). sakadmin verifies the signature of each image
  digest offline, against the sigstore root built into sakadmin, before it runs the image (canary, tools) or gives it
  to the server by digest (backend, ingress). An image without the signature is not pulled. A tag set with
  `SAK_DOCKER_IMAGE_TAG` that no workflow pushes is pulled unchecked. Third-party images (victorialogs, vector) are
  pinned by digest.
