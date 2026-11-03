---
title: "Glossary"
description: "The canonical vocabulary of the repository."
weight: 30
---

# Glossary

The canonical vocabulary of this repository. Use these terms in code, documentation and the
user interface. Use the same term for the same thing every time.

- **Swiss Army Knife**, short **SAK**: The name of the product, in the user interface, the documentation and the
  code. Write it as three words, `Swiss Army Knife`, or as `SAK`; never `SwissArmyKnife`. Lower case `sak` is only a technical prefix, for example
  `sakctl`, `sak-backend` or `sak.pelle.io/name`; a swiss army knife in lower case is the tool the icon shows.
- **SAK Notes** and **SAK Grocery List**: The names of the apps where they are installed: the Android app, its
  listing in Google Play and the installed web app (`name` and `short_name` of `<app>.webmanifest`, which a test
  checks against `app_name` of `i18n/texts.yml`). They are the same in every language. Inside SAK, where the
  prefix is clear, the app has its short name of `app.*` in `i18n/texts.yml`: "Notes" ("Notizen") and "Grocery"
  ("Einkaufslisten"), for example in the list of the apps of the web frontend.
- **deployment** and **SAK instance**: A SAK on its server, with its name, domains and data. Code and the
  documentation for developers (`docs/development/`) say **deployment**. Everything a person reads says **SAK
  instance** (German "SAK-Instanz"): the documentation for users (`README.md`, the other pages of `docs/`), the texts
  of sakctl (its UI, its command line and its help) and the texts of the apps (web, Android, the messages of the
  backend). The verb stays: the command `sakctl deploy`, **Deploy changes**, "a deploy" (German "ausrollen"). A term
  of another tool keeps its word, e.g. the "deployment target" of a static site generator.
- **name**: The unique name of a SAK deployment. Each deployment has one name. Two
  deployments cannot have the same name. Each cloud resource of a deployment has
  the label `sak.pelle.io/name=<name>` (refer to "Cloud resources" in
  [SYSTEM.md](architecture/SYSTEM.md)).
- **dns mode** (`dns_mode`): How SAK sets up DNS for a deployment. The mode `domain` manages `<root domain>` and
  `*.<root domain>`. The mode `subdomain` manages `<name>.<root domain>` and `*.<name>.<root domain>`. The
  default is `subdomain` (refer to [FDR-001](fdr/FDR-001-dns-mode.md)).
- **user mode** (`user_mode`): Who signs in to a deployment. The mode `single` has one user, the admin, who
  signs in with the password of the deployment. The mode `multi` needs a mail server; its users, whom the admin
  adds, sign in with a link by e-mail. The admin always signs in with the password, with an address (optional)
  also by e-mail.
- **base domain**: The domain the hosts of a deployment are below: the root domain in dns mode
  `domain`, `<name>.<root domain>` in dns mode `subdomain`. The frontend is at
  `sak.<base domain>`, the api at `api.<base domain>`, a bucket at `<bucket>.<base domain>`.
- **SAK address**: The address where the index page of the apps is served, the one users open and sign in at:
  `https://sak.<base domain>`, for example `https://sak.pelle.io`; locally `http://localhost:4200`. Login links
  and the download links of the Android apps lead there. Use **SAK address** for it every time, not "frontend URL",
  "app URL" or "the address of the deployment"; the api at `api.<base domain>` is not the SAK address, it is found
  at `<SAK address>/.well-known/sak.json` (refer to `SERVER-6` in [SYSTEM.md](architecture/SYSTEM.md)).
- **root domain** (`root_domain`): The domain under which a deployment serves its apps. The
  production deployment uses `pelle.io`. A local deployment uses `localhost`.
- **delete**: The verb for taking away something a user made or added: a note, a list, an item, a label, a
  bucket, a domain, a redirect, a user. Use it, and the noun **deletion**, every time; never "remove" or "removal".
  Two exceptions: a label, a photo or a recording is **removed from** a note, it stays (the label with its other
  notes, the file in the storage); a deployment is not deleted but **destroyed** (`sakctl destroy`), with its cloud
  resources.
- **setting scope**: Settings that belong together, for example **System**. A setting has one value in its scope.
  The settings page shows each scope as a tab and saves it on its own.
- **backup**: One file with all data of a deployment (the database, the files, the buckets, the state of the
  ingress and the logs), compressed and encrypted with the backup key. A **restore** replaces the data of the deployment of the same
  name with the data of a backup (refer to [FDR-007](fdr/FDR-007-backup.md)).
- **resource status**: What a plan or the state of a deployment says of one of its resources, compared with what the
  deployment declares. It is one of three: **up-to-date** (the resource exists as declared), **missing**
  (it does not exist, also when a resource it depends on does not) or **has changes** (it
  exists but differs). Messages use these words and no others for it, in the sakctl ui and the sakctl cli, the
  status first and then what is done to the resource: `volume 'sak-alpha-data' missing, will be created`. A resource
  found under another name is no status but an error that stops the plan.
- **instance status**: The state of a whole SAK instance: **up-to-date** when every resource is,
  **incomplete** when one is missing, else **has changes**.
