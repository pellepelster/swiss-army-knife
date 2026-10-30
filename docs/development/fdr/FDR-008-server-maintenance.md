---
title: "FDR-008: Server Maintenance"
linkTitle: "Server Maintenance"
description: "Whether a reboot of the server is pending, an update of all its packages, and a coordinated reboot."
weight: 8
---

# FDR-008: Server Maintenance

**Status:** Active
**Last reviewed:** 2026-10-30

## Overview

The server of a deployment installs security updates on its own (unattended upgrades). The user can also see whether
a reboot is pending, upgrade all packages at once, and reboot the server in a way that keeps the data of the
deployment safe. `sak-backend` on the server does the work; sakctl calls it through ssh, as for a backup.

## Behavior

- **Status.** `sakctl maintenance status <name>` shows whether a reboot is pending and why (a newer kernel, or an
  upgrade that asks for it), the services that still run old libraries, and the packages upgraded since the boot.
- **Update.** `sakctl maintenance update <name>` upgrades all packages of the server and shows each package that
  changed, with its old and new version, and whether a reboot is pending after it.
- **Reboot.** `sakctl maintenance reboot <name>` stops the deployment, reboots the server and waits until the
  deployment answers again. `--if-pending` reboots only when a reboot is pending.
- **In the window.** The page of a deployment has a Maintenance menu with Status, Update and Reboot. Each opens a
  view with the lines of the server as it works, then the answer as the command line has it. Cancel stops the
  wait; back to the page of the deployment once it ended. A reboot always reboots and waits until the deployment
  answers again.
- **JSON for scripts.** `--json` prints the answer of the server as it is.
- **One at a time.** A backup, a restore, an update and a reboot never run at the same time. A command that finds
  another one running stops and says so; nothing changes.

## Design Decisions

### 1. One script on the server for all of it

**Decision:** `sak-backend <command>` holds the backup, the restore and the maintenance; the commands share one lock.
**Why:** They all change the server or need it unchanged while they run. One lock keeps an update or a reboot from
interrupting a backup.
**Tradeoff:** A long backup delays an update or a reboot; the command stops and must be run again later.

### 2. Upgrade without removing

**Decision:** The update installs the upgrades and new dependencies, but removes no package, and keeps the
configuration files that the provisioning changed.
**Why:** A package that goes away can take a part of the server with it; a removal needs a person who looks at it.
**Tradeoff:** An upgrade that needs a package removed waits for the next release of the distribution.

### 3. A reboot stops the deployment first

**Decision:** The reboot waits for apt and dpkg, stops the ingress first (no new requests), then the backend and the
logs, and reboots the server some seconds later, so the answer still reaches sakctl. The lock stays held until the
server goes down.
**Why:** The database is closed cleanly, and no backup or restore starts in the last seconds.
**Tradeoff:** The apps are not available from the stop until the server is up again, a minute or two.

### 4. The answer as JSON

**Decision:** Every maintenance command answers one JSON document on its standard output; its progress goes to
standard error.
**Why:** sakctl reads the answer as data and shows it in the user's language; scripts get the same data with `--json`.
**Tradeoff:** None worth noting.

## Related

- **FDRs:** [FDR-007](FDR-007-backup.md) (the backup and restore that share the script and its lock)

## Open Questions

- The automatic reboot of the unattended upgrades does not stop the deployment first
  ([#130](https://github.com/pellepelster/swiss-army-knife/issues/130)).
