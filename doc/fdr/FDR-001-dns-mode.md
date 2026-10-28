# FDR-001: DNS Mode

**Status:** Active
**Last reviewed:** 2026-10-28

## Overview

The DNS mode sets which names of the root domain a SAK deployment manages. With it, a user gives a full domain to one
deployment, or puts more than one deployment under one root domain.

## Behavior

- **Domain:** the deployment manages `<root domain>` and `*.<root domain>`.
- **Subdomain** (the default): the deployment manages `<sak name>.<root domain>` and `*.<sak name>.<root domain>`.
- Each managed name has an A and an AAAA record to the server of the deployment.
- The user selects the root domain from the DNS zones of the cloud project, and the mode, in the wizard window and on
  the command line. The window shows how the names of the apps are made.
- A root domain has one domain deployment or only subdomain deployments. The wizard window does not continue, and
  the command line stops with an exit code for each case: the root domain has a domain deployment already, or the
  modes are mixed. The records of the deployment itself do not count.
- SAK never changes a record without the label of the deployment. Such a record at a managed name stops the deploy
  before anything is created.
- The mode can change. The next deploy sets up the new names and removes the records of the old mode.
- A destroy removes the records of the deployment, never the zone.

## Design Decisions

### 1. Two modes

**Decision:** A domain mode and a subdomain mode.
**Why:** A dedicated domain wants the apps at its top. A domain that has other uses, or more than one deployment,
must not go to one deployment.
**Tradeoff:** Two DNS layouts to set up, test and explain.

### 2. The sak name is the subdomain

**Decision:** In subdomain mode, the subdomain is the sak name.
**Why:** The sak name is unique and a valid DNS label, so two deployments cannot use the same names.
**Tradeoff:** A new subdomain is a new sak name.

### 3. A wildcard next to the base name

**Decision:** Each mode manages the base name and a wildcard below it.
**Why:** The frontend is at `sak.` and the api at `api.` of the base name, buckets at `<bucket>.`. A new name needs no
new record.
**Tradeoff:** All names below the base name go to the deployment, also names that no app uses.

### 4. One mode for each root domain

**Decision:** Domain and subdomain deployments never share a root domain.
**Why:** The wildcard of a domain deployment covers the names of the subdomain deployments.
**Tradeoff:** To add a subdomain deployment, a domain deployment must first change to subdomain mode. The wizard finds
the deployments of a root domain by the labels on the zone and its records. A record with a mode but no sak name
counts as another deployment.

### 5. The zone and its other records belong to the user

**Decision:** SAK uses a zone that exists, and changes or removes only the records with the label of the deployment
(refer to "Resource labeling" in [AGENT.md](../../AGENT.md)).
**Why:** A root domain often holds records that are not part of SAK (mail, verification, other services), and in
subdomain mode, other deployments.
**Tradeoff:** The user creates the zone first, and removes records at the managed names that SAK must not take over.

### 6. The mode can change

**Decision:** A deploy changes the mode and removes the records of the old one.
**Why:** A deployment can move between a subdomain and a dedicated domain without a new deployment. Old records keep
old names in use, and an old domain wildcard covers the names of other deployments.
**Tradeoff:** The addresses of all apps change, the old ones stop at once.

### 7. Subdomain is the default

**Decision:** A new deployment uses subdomain mode, unless the user selects domain mode.
**Why:** Subdomain mode cannot take a full domain from the user or from other deployments by mistake.
**Tradeoff:** A user with a dedicated domain must select domain mode.

### 8. The mode is kept on the resources

**Decision:** Each resource of the deployment carries its mode and root domain, next to the sak name.
**Why:** The wizard keeps no local state. A deploy from another computer, or from the command line, knows the mode.
**Tradeoff:** A change of the mode changes each resource.

## Related

- **ADRs:** none
- **FDRs:** none
