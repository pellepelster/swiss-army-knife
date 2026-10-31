---
title: "Redirects"
description: "The app that redirects domains to other domains or to a bucket."
weight: 20
---

# Redirects

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Redirects every request for a domain to another domain, the path kept, with a permanent redirect.

## Parts

| Part | What it is |
|---|---|
| `io.pelle.sak.backend.redirects` | The redirects (`RedirectService`), their health, the routes of the app |
| `sak-frontend/src/app/redirects/` | The list of the redirects and the page of a redirect |
| `redirects` | A redirected domain with its target |

## Data

- **A redirect** has its source domain, which can not be changed, and a target: an external domain or a bucket.
- **The location** is the domain a redirect leads to: the external domain, or the first domain the bucket is served
  at. A redirect to a bucket that is served at no domain leads nowhere and is left out of caddy.

## API

Create, change the target of, read and delete a redirect.

## Settings

None.

## External systems

None.

## Decisions

- **A bucket as the target** follows the bucket: a redirect leads to where the bucket is served now.
- **A redirected domain** is no domain of the apps and of no bucket (refer to [the architecture of the apps](INDEX.md#shared-parts)).
- **Caddy is configured right away** after every change, not only at the next period.
