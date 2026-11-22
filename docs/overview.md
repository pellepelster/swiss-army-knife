---
title: "Overview"
description: "How sakadmin on your computer and your SAK instance in the cloud belong together."
weight: 1
---

# Overview

Swiss Army Knife has two parts: **sakadmin**, a program on your computer, and a **SAK instance**, your own server in the
cloud that runs the apps. sakadmin sets up and looks after the SAK instance. You and your users need only a browser or a
phone.

```text
        your computer                                   Hetzner Cloud
 ┌───────────────────────────┐              ┌──────────────────────────────────────┐
 │ sakadmin                  │   creates,   │ your SAK instance                    │
 │  UI or command line       │──deploys,───▶│  server, data volume, addresses,     │
 │                           │   backs up   │  firewall, DNS records               │
 │ ~/.sak/<name>/backups     │◀─────────────│  the apps: notes, grocery lists,     │
 └───────────────────────────┘              │  todo lists, static hosting,         │
                                            │  redirects                           │
                                            └──────────────────────────────────────┘
                                                      ▲
                                 browser, phone ──────┘  you and your users
```

## sakadmin

sakadmin has a UI and a command line. With the API token of your Hetzner Cloud project, it:

- **creates** a SAK instance: server, data volume, addresses, firewall, DNS records and the apps;
- **deploys** it again, to change its settings or update it;
- **backs up** all its data into one encrypted file on your computer, and **restores** it;
- **maintains** its server: package upgrades and reboots;
- **destroys** it, and keeps its data volume unless you ask otherwise.

Before a change, sakadmin shows a plan of what it creates, changes or deletes.

## The SAK instance

A SAK instance is one server at Hetzner Cloud with a name, for example `family`, and a root domain, for example
`example.org`. Its apps are at its SAK address, where you and your users sign in. The **DNS mode** sets this address:

- **Subdomain** (default): `sak.<name>.<root domain>`, for example `sak.family.example.org`. More SAK instances can
  use the same root domain.
- **Domain**: `sak.<root domain>`, for example `sak.example.org`. Only this SAK instance can use the root domain.

The apps and the websites of the buckets are below the **base domain**: `<name>.<root domain>` in the DNS mode
**Subdomain**, the root domain itself in the DNS mode **Domain**. A bucket `blog` is so served at
`blog.family.example.org` or at `blog.example.org`.

All data is on the data volume, a disk that outlives the server: a deploy can replace the server, the data volume
stays.

The SAK instance runs on its own. You need sakadmin only to set it up, change or update it, back it up and maintain it.

## The password

sakadmin finds a SAK instance again with its name and password alone. On your computer it keeps only the backups and
the settings of the last deploy, encrypted with the password (`~/.sak/<name>/`). All keys of the SAK instance, for the
server and for the backups, come from the password. So:

- sakadmin on another computer, with the same name, password and Hetzner token, manages the same SAK instance;
- the admin signs in to the apps with the same password;
- nobody can recover a lost password, and the backups made with it are lost too.

## Versions

sakadmin and the SAK instance have the same version. `sakadmin update` (or **Check for updates** in the UI) updates
sakadmin; the next deploy brings the SAK instance to the same version.

Next: [install sakadmin](installation.md). Later: [look after your SAK instance](manage.md).
