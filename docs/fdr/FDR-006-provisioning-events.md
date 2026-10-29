---
title: "FDR-006: Provisioning Events"
linkTitle: "Provisioning Events"
description: "The playbook reports each step of a provisioning to sakctl as a structured event."
weight: 6
---

# FDR-006: Provisioning Events

**Status:** Planned
**Last reviewed:** 2026-10-29

## Overview

While sakctl provisions a server, Ansible runs in a container and sakctl sees only the text it prints. This
feature lets the playbook report each step to sakctl as a structured event: which play and task run, and how each
ended on the server. The window and the command line show the progress from these events instead of the raw output
of Ansible.

## Behavior

- While provisioning, sakctl shows the play and the task that runs, and for each finished task whether it changed
  something, was skipped or failed. At the end it shows the counts of the run.
- A failed task shows its name and the message Ansible gives, as the reason the provisioning failed.
- The raw output of Ansible is still there: on the command line with `--show-log`, in the window behind a toggle.
- The events go only to the sakctl that started the run. Nothing else on the machine or on the network can read or
  send them.
- When the events cannot be delivered, the provisioning goes on and ends as it would without them; sakctl then shows
  the raw output.

## Design Decisions

### 1. The playbook pushes events to sakctl over http

**Decision:** A callback of the Ansible collection posts each event as JSON to an endpoint sakctl serves for the
time of one run, the way blcks reports its runs.
**Why:** Structured events need no parsing of text meant for people, and the same events can later go to the
backend of a deployment.
**Tradeoff:** A small http server in sakctl, and a way for the container to reach it.

### 2. The endpoint is on the loopback of the machine only

**Decision:** sakctl listens on the loopback address of the machine. On Linux the container shares the network of
the machine; with Docker Desktop and Podman on macOS or Windows it reaches the loopback through the name these
provide for the machine.
**Why:** Nothing on the network can reach the endpoint, and no firewall rule is needed.
**Tradeoff:** On Linux the container sees every port the machine listens on at its loopback; the container runs
only the playbook of SAK.

### 3. Every run has a token of its own

**Decision:** sakctl creates a random token for each run and refuses every event without it. The container gets
the token through the private directory of the run, not on the command line.
**Why:** Another process on the machine cannot send or fake events, and the token does not show in the list of
processes.
**Tradeoff:** None known.

### 4. Events never stop a run

**Decision:** A delivery that fails or takes long is given up after a short time, and the playbook goes on.
**Why:** Provisioning a server matters more than showing its progress.
**Tradeoff:** A run whose events are lost shows only the raw output.

## Related

- **ADRs:** none
- **FDRs:** none
