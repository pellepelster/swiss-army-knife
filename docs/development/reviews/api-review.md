---
title: "Review: API"
linkTitle: "API"
description: "The review of the routes below /api/v1 against the API contract (API-1 to API-4 and API-6)."
weight: 2
---

# Review: API

**Created:** 2026-10-31

**Checked again:** 2026-11-12, every finding listed is still open; the fixed ones are in the history of git.

Scope: all routes below `/api/v1`: the login (`sak-auth`), the buckets and their access logs (`sak-logs`), the
redirects, the labels, the notes, the grocery lists, the files, the settings, the configuration, the health and the
info. The contract is in [SYSTEM.md](../architecture/SYSTEM.md#api).

What is good: every route has its OpenAPI description, the errors of a request are one readable sentence each, the
lists are paged with the same parameters, the times use `apiTime()`, and no domain class crosses the API (`API-4`).

## To think about

### ~~`AR-6` Grocery and todo have no read of one list or one item~~

**Fixed:** `GET /lists/{id}` and `GET /items/{id}` of grocery and todo answer a list and an item the user sees, else 404.

Every other resource has `GET /{id}` (buckets, redirects, labels, notes). Grocery and todo have `GET /lists` and
`GET /lists/{id}/items`, but no `GET /lists/{id}` and no `GET /items/{id}`. The page reads all lists to show one.

### ~~`AR-7` The suggestions page at most ten items~~

**Fixed:** the suggestions are paged in the database like every list, over all of them, with their total; the web app
and the Android app ask for the first page of ten.

The service of `GET /api/v1/grocery/lists/{id}/suggestions` gives at most `MAX_SUGGESTIONS` (10) items, which the
route then pages; `limit=50` gives 10, and an `offset` of 10 or more gives none. Either no pages (a fixed short list
with `limit` as its size), or the pages over all suggestions.

## Small items

- `AR-8` `BucketResponse.subdomain` is missing "for a name with dots, which were allowed before";
  `S3BucketService.subdomain` checks the name only for these old names. No bucket of today has a dot. Remove the
  check and the comment; `subdomain` is then missing only without a base domain.
