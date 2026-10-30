---
title: "FDR-009: Provisioning Markers"
linkTitle: "Provisioning Markers"
description: "The provisioning of the server as a resource of the plan, its changes found by markers of the roles on the server."
weight: 9
---

# FDR-009: Provisioning Markers

**Status:** Active
**Last reviewed:** 2026-10-30

## Overview

A deploy sets up the server with the roles of the ansible collection. The plan tells whether the roles have to run
again, the same way it tells it for the cloud resources. Ansible has no plan of its own, so the server keeps what was
applied last, and the plan compares it with what this sakctl would apply.

## Behavior

- **One row of the plan.** The provisioning shows in the plan, in the window and on the command line, after the
  server and the data volume: up-to-date, missing or with changes, like every resource.
- **A role runs again when it changes.** The playbook runs when the server is created or changes, when the server has
  no markers, or when a role has another fingerprint than the marker on the server. The changes name the roles.
- **A failed run shows.** A run removes the markers before the roles run and writes them after the last role, so a
  run that failed leaves none, and the next plan finds the provisioning missing.
- **Force deploy.** A forced deploy runs the playbook also when every role is up to date.
- **A new version.** A new sakctl runs the role `backend` again (it gets the version, the tag of its images). The
  other roles run again only when their files or their values changed.

## Design Decisions

### 1. Markers on the server, written by the run

**Decision:** The last role of the playbook writes one file for each role to `/var/lib/sak/roles/<role>`, its
fingerprint. The plan reads them through ssh, in the image of the tools.
**Why:** The server itself says what it has; a server created anew has no markers. sakctl keeps no state.
**Tradeoff:** The plan needs ssh to the server, which takes some seconds. A server that does not answer shows the
provisioning with changes.

### 2. A fingerprint of the files and the values

**Decision:** The fingerprint of a role is the sha-256 over the files of the role (taken when sakctl is built, the
image of the tools of the same build has the same files) and over the values of the inventory the role reads. A test
checks that each role reads only the values it names.
**Why:** A release that changed the tasks of a role, and a deploy that gives a role another value, both run it again;
nothing else does.
**Tradeoff:** A value a role reads but does not name would not run it again; the test prevents that.

### 3. Only root reads the markers

**Decision:** The directory is readable by root only.
**Why:** The fingerprint of the role `backend` covers its secrets (the password of the mail server).
**Tradeoff:** None worth noting, sakctl logs in as root.

### 4. The provisioning is a resource of sakctl

**Decision:** sakctl declares the roles as a resource with a provisioner of its own and adds it to the registry of
the cloud resources. The cloud library knows nothing of ansible, ssh or containers.
**Why:** One plan, one apply in the order of the dependencies, the same rows and events for every resource.
**Tradeoff:** The registry forgets the pending changes once it applies, so the run tells the provisioner when it
changed the server.

## Related

- **FDRs:** [FDR-006](FDR-006-provisioning-events.md) (the events of the playbook while it runs)

## Open Questions

- The playbook runs all roles when one of them changed; running only the changed roles is possible later.
- A server provisioned by an older sakctl has no markers; its first plan shows the provisioning missing.
