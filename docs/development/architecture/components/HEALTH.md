---
title: "Health"
description: "The page that shows whether the domains of the buckets and the redirects point to the server."
weight: 47
---

# Health

A maintenance page, no app (refer to [the architecture of the components](INDEX.md#the-components)). Adds to it and to
[SYSTEM.md](../SYSTEM.md).

## Purpose

Shows whether everything the backend serves can be reached: every domain of a bucket and every redirected domain
must point to the server. Its checks are shown on the status page ([STATUS.md](STATUS.md)).

## Data

None stored. Every check runs on each request, all at once.

## API

`GET /api/v1/health`: each check with its state (healthy, unhealthy, unknown) and the state of each domain.

## External systems

- `HEALTH-1` **Name servers are asked directly.** The backend asks them for the domains; they see the address of the
  server and the names asked for.

## Decisions

- `HEALTH-2` **Unknown without addresses.** Without the addresses of the server in the configuration, e.g. locally, a
  domain can not be checked and is unknown.
- `HEALTH-3` **The system is as healthy as its worst check.** The health of the whole system is unhealthy when a
  check is, else unknown when a check could not tell, else healthy; without a check it is healthy.
