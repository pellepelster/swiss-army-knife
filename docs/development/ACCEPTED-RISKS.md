---
title: "Accepted Risks"
description: "The known weaknesses of SAK that are kept on purpose, each with its trade-off."
weight: 15
---

# Accepted Risks

Known weaknesses kept on purpose. A change that touches one keeps the trade-off or revisits it here. How SAK is built
is in [SYSTEM.md](architecture/SYSTEM.md).

- **The database is not encrypted at rest.** The SQLite file on the data volume, secrets included, is plain.
  Accepted until [FDR-002](fdr/FDR-002-sqlite-encryption.md) encrypts the whole file.

- **The files are not encrypted at rest.** The files of the file storage, their thumbnails and the objects of the
  buckets are plain on the data volume. Accepted until
  [issue #134](https://github.com/pellepelster/swiss-army-knife/issues/134) encrypts them in `sak-storage`
  (`MODEL-7`).

- **A provisioning run writes the mail server password to disk.** The environment of the backend and the token of the
  events endpoint are plain files on the user's machine while the run lasts. Accepted: only the user can read the
  directory, it has a random name, lives for minutes, and a killed run's leftovers go on the next run.

- **The password can be guessed offline.** Whoever reads the cloud project (the password hash label, the public keys)
  or the server (the admin password hash) can test guesses without asking anybody. Accepted: every guess costs a
  strong argon2id, and weak passwords are refused by a strength estimate. The salt is the deployment name, not unique
  across users, because a salt to keep would break "no local state"; the cost per guess keeps tables out of reach.

- **Label hashes are shortened.** A label value holds 63 characters, so hashes in labels keep 240 of their 256 bits;
  a collision would hide a change. Accepted: about one in 2^120, and one label per hash stays simple.

- **The ssh host key is in the user data of the server.** The cloud provider keeps it, and the metadata service hands
  it out; whoever reads it could impersonate the server towards sakctl. Accepted: the firewall lets only root reach
  the metadata service, and root or the cloud project already have more than the host key gives.

- **sakctl can read the mail server password.** It fetches the settings of a deployment with a signed request
  (`GET /api/v1/deployment`, sessions get 401) and keeps them encrypted in `~/.sak/<name>/config.json`, so a redeploy
  needs no retyping. Whoever has the deployment password can read the mail password. Accepted: the same people can
  read it on the server, and the mail account is meant for this deployment's login mails only.

- **A signed request can be relayed while fresh.** Whoever controls the DNS zone (it is in the cloud project) and
  gets a certificate for `api.<base domain>` can pass one request of sakctl on to the backend within its two minutes,
  once. Accepted: it is the request sakctl meant to make, and the signature holds only for the backend's own host.

- **The login links can be used up** ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). At most 60
  links an hour go out, and 5 an hour per address and client IP; 12 client IPs can block logins for an hour.
  Accepted: the global limit keeps the server from sending spam, and sessions last 30 days.

- **The sakctl UI gets passwords from the environment.** `SAK_PASSWORD` and `SAK_SMTP_PASSWORD` fill its password
  fields, so they cross into the web view. Accepted: the web view loads only sakctl's pages under a strict content
  security policy and writes values as text; the same process holds the secrets anyway.

- **Two runs can hit one deployment at once.** Two sakctl, on two machines or UI and command line, can deploy or
  destroy at the same time; only the server's own commands (backup, restore, update, reboot) take a lock. Accepted:
  a deployment has one user who starts runs by hand, every run plans first, and the next deploy repairs a half-done
  run. A lock label would stay behind after a killed run.

- **The client key passes the container engine.** sakctl hands the private ssh key to an agent in a container and
  to the provisioning container; it never touches disk, but with a remote, unencrypted `DOCKER_HOST` it would cross
  the network. Accepted for now: sakctl expects the local engine. To revisit: refuse a plain tcp engine.

- **The trusted root of sigstore is built into sakctl.** An update is verified offline against the trusted root of
  the sakctl that installs it, so no host but GitHub sees the update. If sigstore replaces its root keys, a sakctl
  older than the change can no longer verify a new release and has to be installed again by hand. Accepted: the keys
  of the root change rarely and with notice. To revisit: fetch the trusted root through the TUF repository of
  sigstore.

- **The server checks no signature of an image.** sakctl verifies the images and hands the server their digests, so
  the server pulls exactly the images sakctl verified; a sakctl that does not verify (a tag set with
  `SAK_DOCKER_IMAGE_TAG`) hands it tags. Accepted: podman reads only the older signatures of cosign, not the bundles
  of cosign 3. To revisit when podman reads them (issue #145).

- **A label can be lost while a note is saved.** If the user deletes a new label on another device in the same
  moment, the note is saved without it; if the note is deleted, the label stays without a note. Accepted: it takes
  two devices within milliseconds, and the user sees and fixes it at once.

- **A session ends only when its token expires.** Signing out forgets the token on the device; the backend keeps no
  session list, so a copied token stays valid for the session TTL. Accepted: the token stays on the device (encrypted
  with the keystore on Android) and travels only over https; revoking would be the first session state in the
  backend. Revisit when shared devices make theft likely.
