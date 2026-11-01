---
title: "Overview"
description: "How sakctl on your computer and your deployment in the cloud belong together."
weight: 1
---

# Overview

Swiss Army Knife has two parts: **sakctl**, a program on your computer, and a **deployment**, your own server in the
cloud that runs the apps. sakctl sets up and looks after the deployment; you and the people you invite use the apps
of the deployment in a browser or on a phone, without sakctl.

```text
        your computer                                   Hetzner Cloud
 ┌───────────────────────────┐              ┌──────────────────────────────────────┐
 │ sakctl                    │   creates,   │ your deployment                      │
 │  sakctl ui or sakctl cli  │──deploys,───▶│  server, data volume, addresses,     │
 │                           │   backs up   │  firewall, DNS records               │
 │ ~/.sak/<name>/backups     │◀─────────────│  the apps: notes, grocery lists,     │
 └───────────────────────────┘              │  static hosting, redirects           │
                                            └──────────────────────────────────────┘
                                                      ▲
                                 browser, phone ──────┘  you and your users
```

## sakctl

sakctl runs on your computer, as the sakctl ui (a window) or the sakctl cli (commands in a terminal). With the API
token of your Hetzner Cloud project it:

- **creates** a deployment: the server, its data volume, its addresses, its firewall and the DNS records of its
  domain, then sets up the server and the apps;
- **deploys** it again, to change its settings or to update it to the version of sakctl;
- **backs up** all its data into one encrypted file on your computer, and **restores** a backup into it;
- **maintains** its server: upgrades its packages and reboots it;
- **destroys** it, and keeps its data volume unless you ask to delete that too.

Before anything changes, sakctl shows a plan of what it will create, change or delete.

## The deployment

A deployment is one server at Hetzner Cloud, with a name, for example `family`, and a domain. Its apps are at
`sak.<domain>`; this is where you and your users sign in. All its data, the notes, the lists, the files of the
websites, is on its data volume, a disk of its own that outlives the server: a deploy may create the server anew, the
data volume stays.

The deployment runs on its own. sakctl is only needed to set it up, to change or update it, and for backups and
maintenance; it does not have to run, and your computer does not have to be on, for the apps to work.

## The password

The name and the password of a deployment are all sakctl needs to find it again: sakctl keeps nothing on your
computer but the backups. Every key of the deployment, the one sakctl reaches the server with and the one that encrypts
the backups, is derived from the password. So:

- sakctl on another computer, with the same name, password and Hetzner token, manages the same deployment;
- the password is also the one of the admin who signs in to the apps;
- a lost password can not be recovered, and neither can the backups made with it.

## Versions

sakctl and the deployment have the same version. `sakctl update` (or **Check for updates** in the sakctl ui)
updates sakctl; the next deploy brings the deployment to the same version.

Next: [install sakctl](installation.md).
