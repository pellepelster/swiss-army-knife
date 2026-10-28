# FDR-001: DNS Deployment Mode

**Status:** Planned
**Last reviewed:** 2026-10-28

## Overview

The DNS deployment mode sets how SAK sets up DNS for a SAK deployment. The user selects a mode for each deployment. The
mode sets which names under the root domain SAK manages. With the two modes, a user can give a full domain to one
deployment, or put more than one deployment under one root domain.

## Behavior

- Each deployment has one DNS deployment mode.
- **Whole domain:** SAK manages `<root domain>` and `*.<root domain>`. All names under the root domain belong to this
  deployment.
- **Subdomain:** SAK manages `<sak name>.<root domain>` and `*.<sak name>.<root domain>`. SAK does not change other
  names under the root domain.
- The managed names point to the server of the deployment.
- A root domain has one whole-domain deployment or one or more subdomain deployments, never both.
- The DNS zone of the root domain must exist before the deployment. SAK does not create it.
- A destroy removes the DNS records that SAK created for the deployment. It never removes the DNS zone.
- The user can change the mode of an existing deployment. The next deploy sets up the names of the new mode and
  removes all DNS records of the old mode.

## Design Decisions

### 1. Two modes, not one

**Decision:** SAK supports a whole-domain mode and a subdomain mode.
**Why:** A user with a dedicated domain wants the apps directly under the root domain. A user who already uses the
domain for other things, or who has more than one deployment, must not give the full domain to SAK.
**Tradeoff:** Two DNS layouts to set up, test and explain.

### 2. The sak name is the subdomain

**Decision:** In subdomain mode, the subdomain of a deployment is its sak name.
**Why:** The sak name is unique for each deployment and is a valid DNS label. Thus two deployments under the same root
domain cannot use the same names.
**Tradeoff:** A change of the subdomain is a change of the sak name.

### 3. A wildcard next to the base name

**Decision:** Each mode manages the base name and a wildcard below it.
**Why:** The apps of a deployment use subdomains of the managed name (for example `api.`). With the wildcard, a new app
does not need a new DNS record.
**Tradeoff:** All names below the base name go to the deployment, also names that no app uses.

### 4. The zone belongs to the user

**Decision:** SAK uses a DNS zone that exists and never creates or removes it. A destroy removes only the labeled
records of the deployment (refer to "Resource labeling" in [AGENT.md](../../AGENT.md)) and keeps the zone.
**Why:** The root domain often holds records that are not part of SAK (mail, verification records, other services). A
destroy of a deployment must not remove them. In subdomain mode, more than one deployment uses the same zone. The
records of a destroyed deployment point to a server that no longer exists, so the destroy removes them.
**Tradeoff:** The user must create the zone at the DNS provider before the first deploy.

### 5. The mode can change

**Decision:** The user can change the mode of an existing deployment. The deploy after the change removes all DNS
records of the old mode.
**Why:** A deployment can start under a subdomain and move to a dedicated domain later, or the opposite, without a new
deployment.
The records of the old mode would keep the old names in use, and in whole-domain mode their wildcard would cover the
names of other deployments (refer to decision 6).
**Tradeoff:** A change of the mode changes the addresses of all apps of the deployment. The old addresses stop to work
at once.

### 6. One mode for each root domain

**Decision:** A root domain has a single whole-domain deployment or one or more subdomain deployments. The two modes
never use the same root domain.
**Why:** The wildcard of a whole-domain deployment (`*.<root domain>`) also covers the names of subdomain deployments.
Two deployments would then manage the same names.
**Tradeoff:** To add a subdomain deployment to a domain with a whole-domain deployment, the user must first change the
whole-domain deployment to subdomain mode.

## Related

- **ADRs:** none
- **FDRs:** none
