---
title: "Accepted Risks"
description: "The known weaknesses of SAK that are kept on purpose, each with its trade-off."
weight: 15
---

# Accepted Risks

Known weaknesses kept on purpose. A change that touches one keeps the trade-off or revisits it here. How SAK is built
is in [SYSTEM.md](architecture/SYSTEM.md).

- **The database is not encrypted at rest.** The SQLite file on the data volume, secrets included, is plain: the
  settings with the signing key of the login, the secret keys of the buckets, the tokens of the repositories of
  restic and the paths of the backup plans. Accepted until the whole file is encrypted.

- **The files are not encrypted at rest.** The files of the file storage, their thumbnails and the objects of the
  buckets are plain on the data volume. Accepted until
  [issue #134](https://github.com/pellepelster/swiss-army-knife/issues/134) encrypts them in `sak-storage`
  (`MODEL-7`).

- **Not all data outside the database has a resource id yet.** The objects of a bucket are stored below the name of
  the bucket, not below a resource id (`MODEL-8`). Accepted until the buckets move to a resource id: a deleted bucket
  keeps its name, so no other row gets its data.

- **A backup during the move of the files to their resource ids can miss files.** The first start of the release
  that brought the resource ids of the files moves their content from below its sha-256 (`FileLayoutMigration`).
  A backup walks the files while the backend runs; one that walks them during the move can see the directory of a
  file before its content arrived and the old one after it left. Accepted: the move takes seconds once, a backup
  then is avoided, and every start warns of the files whose content is in neither place.

- **A provisioning run writes the mail server password to disk.** The environment of the backend and the token of the
  events endpoint are plain files on the user's machine while the run lasts. Accepted: only the user can read the
  directory, it has a random name, lives for minutes, and a killed run's leftovers go on the next run.

- **The password can be guessed offline.** Whoever reads the cloud project (the password hash label, the public keys) or
  the server (the admin password hash) can test guesses without asking anybody. Accepted: every guess costs a strong
  argon2id, and weak passwords are refused by a strength estimate. The salt is the deployment name, not unique across
  users, because a salt to keep would be state on one machine, and two machines with the same password must manage the
  same deployment (`SAKADM-2`); the cost per guess keeps tables out of reach.

- **Label hashes are shortened.** A label value holds 63 characters, so hashes in labels keep 240 of their 256 bits;
  a collision would hide a change. Accepted: about one in 2^120, and one label per hash stays simple.

- **The ssh host key is in the user data of the server.** The cloud provider keeps it, and the metadata service hands
  it out; whoever reads it could impersonate the server towards sakadmin. Accepted: the firewall lets only root reach
  the metadata service, and root or the cloud project already have more than the host key gives.

- **sakadmin can read the mail server password.** It fetches the settings of a deployment with a signed request
  (`GET /api/v1/deployment`, sessions get 401) and keeps them encrypted in `~/.sak/<name>/config.json`, so a redeploy
  needs no retyping. Whoever has the deployment password can read the mail password. Accepted: the same people can
  read it on the server, and the mail account is meant for this deployment's login mails only.

- **A signed request can be relayed while fresh.** Whoever controls the DNS zone (it is in the cloud project) and
  gets a certificate for `api.<base domain>` can pass one request of sakadmin on to the backend within its two minutes,
  once. Accepted: it is the request sakadmin meant to make, and the signature holds only for the backend's own host.

- **The login links can be used up** ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). At most 60
  links an hour go out, and 5 an hour per address and client IP; 12 client IPs can block logins for an hour.
  Accepted: the global limit keeps the server from sending spam, and sessions last 30 days.

- **The sakadmin UI gets passwords from the environment.** `SAK_PASSWORD` and `SAK_SMTP_PASSWORD` fill its password
  fields, so they cross into the web view; so does the password of the mail server that sakadmin reads from a running
  deployment. Accepted: the web view loads only sakadmin's pages under a strict content
  security policy and writes values as text; the same process holds the secrets anyway.

- **Two runs can hit one deployment at once.** Two sakadmin, on two machines or UI and command line, can deploy or
  destroy at the same time; only the server's own commands (backup, restore, update, reboot) take a lock. Accepted:
  a deployment has one user who starts runs by hand, every run plans first, and the next deploy repairs a half-done
  run. A lock label would stay behind after a killed run.

- **The client key passes the container engine.** sakadmin hands the private ssh key to an agent in a container and
  to the provisioning container; it never touches disk, but with a remote, unencrypted `DOCKER_HOST` it would cross
  the network. Accepted for now: sakadmin expects the local engine. To revisit: refuse a plain tcp engine.

- **The trusted root of sigstore is built into sakadmin.** An update is verified offline against the trusted root of
  the sakadmin that installs it, so no host but GitHub sees the update. If sigstore replaces its root keys, a sakadmin
  older than the change can no longer verify a new release and has to be installed again by hand. Accepted: the keys
  of the root change rarely and with notice. To revisit: fetch the trusted root through the TUF repository of
  sigstore.

- **The server checks no signature of an image.** sakadmin verifies the images and hands the server their digests, so
  the server pulls exactly the images sakadmin verified; a sakadmin that does not verify (a tag set with
  `SAK_DOCKER_IMAGE_TAG`) hands it tags. Accepted: podman reads only the older signatures of cosign, not the bundles
  of cosign 3. To revisit when podman reads them (issue #145).

- **A label can be lost while a note is saved.** If the user deletes a new label on another device in the same
  moment, the note is saved without it; if the note is deleted, the label stays without a note. Accepted: it takes
  two devices within milliseconds, and the user sees and fixes it at once.

- **A session ends only when its token expires.** Signing out forgets the token on the device; the backend keeps no
  session list, so a copied token stays valid for the session TTL, and a copied session can get itself a new one of the
  full time through a connect token (`SECURITY-3`), without an end. Accepted until a session has a largest age from the
  login of the user: the token stays on the device (encrypted with the keystore on Android) and travels only over https;
  revoking would be the first session state in the backend. Revisit when shared devices make theft likely.

- **An action waiting for its undo is lost when Android ends the app.** A grocery check or a todo purge waits for
  its undo in memory (`UX-4.2`). Accepted: at most one tap is lost, and the list shows it on the next start.

- **Logs name a user by a short hash of the e-mail address.** Whoever guesses an address can confirm it. Accepted: the
  admin finds a user's lines with `sha256sum` alone.

- **The password of the deployment is typed into the login.** The admin signs in to the web app and the Android apps
  with the password of the deployment itself; the backend keeps only its hash (`SECURITY-1`), but whoever runs code
  in the backend or in caddy reads the password with the next login, and with it derives every key of the
  deployment. Accepted until the login has a password of its own or the server never sees it.

- **The token of a repository of restic deletes all of it.** Whoever has the token, which is on the computer of the
  user, can delete every file of the repository; the server keeps nothing of what was deleted. Accepted for now,
  though the review rates it a must fix: deleting snapshots needs deletes. To revisit: the server keeps deleted
  files for some days, and a token can be replaced.

- **A photographed QR code signs in.** Whoever sees the code that connects an app signs in as its user (`SECURITY-3`).
  Accepted: it is valid for 2 minutes and once, shows only on request, and the page says when an app used it.

- **The desktop apps keep the SAK addresses in a plain file.** sakbackup writes the addresses it signed in at to
  `instances.json` in its config directory, not encrypted. Accepted: it holds addresses only, never a session or a
  token, the file and its directory are for the user only (`0600`, `0700`), and an encryption would need a key in
  the keychain of the system, which not every desktop has.

- **A desktop app keeps its session token in the keychain.** sakbackup keeps the token of each deployment in the
  keychain of the system, valid for up to 30 days. On linux an unlocked Secret Service hands it to every program of
  the user. Accepted: those programs can read the sessions of the browser of the user as well, the token is never in
  a file, a sign-out deletes it, and a deployment that refuses it deletes it at the next start.
