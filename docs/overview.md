---
title: "Overview"
description: "How sakctl on your computer and your deployment in the cloud belong together."
weight: 1
---

# Overview

Swiss Army Knife has two parts: **sakctl**, a program on your computer, and a **deployment**, your own server in the
cloud that runs the apps. sakctl sets up and looks after the deployment; you and your users only need a browser or a
phone.

```text
        your computer                                   Hetzner Cloud
 ┌───────────────────────────┐              ┌──────────────────────────────────────┐
 │ sakctl                    │   creates,   │ your deployment                      │
 │  UI or command line       │──deploys,───▶│  server, data volume, addresses,     │
 │                           │   backs up   │  firewall, DNS records               │
 │ ~/.sak/<name>/backups     │◀─────────────│  the apps: notes, grocery lists,     │
 └───────────────────────────┘              │  static hosting, redirects           │
                                            └──────────────────────────────────────┘
                                                      ▲
                                 browser, phone ──────┘  you and your users
```

## sakctl

sakctl runs on your computer, as a UI or on the command line. With the API token of your Hetzner Cloud project it:

- **creates** a deployment: its server, data volume, addresses, firewall and DNS records, then the apps;
- **deploys** it again, to change its settings or update it;
- **backs up** all its data into one encrypted file on your computer, and **restores** it;
- **maintains** its server: package upgrades and reboots;
- **destroys** it, keeping its data volume unless you ask otherwise.

Before anything changes, sakctl shows a plan of what it will create, change or delete.

## The deployment

A deployment is one server at Hetzner Cloud, with a name, for example `family`, and a domain. Its apps are at
`sak.<domain>`, where you and your users sign in. All its data lives on its data volume, a disk that outlives the
server: a deploy may replace the server, the data volume stays.

The deployment runs on its own. Your computer and sakctl are only needed to set it up, change or update it, and for
backups and maintenance.

## The password

The name and the password of a deployment are all sakctl needs to find it again; it keeps nothing on your computer
but the backups. Every key of the deployment, for the server and for the backups, is derived from the password. So:

- sakctl on another computer, with the same name, password and Hetzner token, manages the same deployment;
- the password is also the one of the admin who signs in to the apps;
- a lost password can not be recovered, and neither can the backups made with it.

## Versions

sakctl and the deployment have the same version. `sakctl update` (or **Check for updates** in the UI) updates sakctl;
the next deploy brings the deployment to the same version.

Next: [install sakctl](installation.md).
