---
title: "Health"
description: "The page that shows whether the domains of the buckets and the redirects point to the server."
weight: 40
---

# Health

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Shows whether everything the backend serves can be reached: every domain of a bucket and every redirected domain
must point to the server.

## Parts

| Part | What it is |
|---|---|
| `io.pelle.sak.backend.health` | The route that runs every check |
| `HostingHealth`, `RedirectsHealth` | The checks of the buckets and of the redirects, in their apps |
| `sak-dns` | The check that a domain points to the addresses of the server |
| `sak-frontend/src/app/health/` | The page of the health |

## Data

None stored. Every check runs on each request, all at once.

## API

`GET /api/v1/health`: each check with its state (healthy, unhealthy, unknown) and the state of each domain.

## Settings

None.

## External systems

- **Name servers** are asked directly for the domains; they see the address of the server and the names asked for.

## Decisions

- **Unknown without addresses.** Without the addresses of the server in the configuration, for example locally, a
  domain can not be checked and is unknown.
