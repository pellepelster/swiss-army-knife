---
title: "Accepted Risks"
description: "The known weaknesses of SAK that are kept on purpose, each with its trade-off."
weight: 15
---

# Accepted Risks

Known weaknesses that are kept on purpose. A change that touches one keeps the trade-off or revisits it here. How SAK
is built is in [SYSTEM.md](architecture/SYSTEM.md).

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

- **The running configuration answers the password of the mail server.** sakctl reads it from the backend with a
  signed request, so a redeploy takes over the whole mail server and nobody types its password again; a session, also
  one of an admin, gets 401. A comfort feature: whoever holds the password of the deployment can read the password of
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
