---
title: "Review: rules of the architecture"
linkTitle: "Rules"
description: "The review of the rules in SYSTEM.md, apps/ and UI-UX.md for consistency among them and with the code."
weight: 4
---

# Review: rules of the architecture

**Created:** 2026-11-02

Scope: the rules with an id in [SYSTEM.md](../architecture/SYSTEM.md), [apps/](../architecture/apps/INDEX.md) and
[UI-UX.md](../UI-UX.md), compared with each other, with the references to them in the code, documents and reviews, and
with the code.

What is good: 109 rules, each id defined once; every id that the code and the documents refer to exists, except one in
a review (`RR-6`). The gaps in the numbers (`API-5`, `CLOUD-5`, `GROCERY-4` to `GROCERY-10`) are rules that went; their
ids are not used again, as the documents require. A change of a rule needs the consent of the user, so this review
changes none.

## Must fix

### `RR-1` `SHARED-1` says every app needs the role `admin`

`SHARED-1`: "every app needs one [a session] and the role `admin`". The notes, the grocery lists, the labels and the
file storage take the role `user` too (`requireRole(Role.ADMIN, Role.USER)` in `SakApps.kt`); only the static hosting,
the redirects, the users, the settings and the health need `admin`.

Fix: `SHARED-1` names the apps of the users and the ones of the admins.

### `RR-2` `SAKCTL-2` says sakctl keeps no local state

`SAKCTL-2`: "Everything sakctl needs is derived from the name and password of a deployment, or read back from the labels
of its resources". sakctl keeps the settings of the last create or deploy of a deployment in
`~/.sak/<name>/config.json` (`LocalSettings`) and reads them on a redeploy before its DNS records; `CLOUD-5` went with
that change, `SAKCTL-2` stayed.

Fix: `SAKCTL-2` names the local settings, what they hold and that a redeploy without them takes the settings from the
deployment.

## Should fix

### `RR-3` `HOSTING-10`, `REDIRECTS-4` and HOSTING's external systems refer to rules that are not there

`HOSTING-10` and `REDIRECTS-4` refer to the shared parts of [apps/INDEX.md](../architecture/apps/INDEX.md#shared-parts)
for which domains belong to the apps, the buckets and the redirects; HOSTING.md refers there for the health of the
domains. The shared parts (`SHARED-1` to `SHARED-3`) say nothing of domains; the health is `HEALTH-1`.

Fix: a shared rule of the domains (one domain belongs to the apps, one bucket or one redirect, `DomainRegistry`), and
the reference of the health to `HEALTH-1`.

### `RR-4` `sak-openrouter` reads more than images

The table of the parts in SYSTEM.md: "The client that reads the text in images (text discovery)". It also writes down
recordings (`OpenRouterSpeechTranscriber`, `NOTES-5`).

Fix: "The client of the AI provider: the text in images and recordings (text discovery)".

### `RR-5` `SECURITY-3` leaves out the API key of OpenRouter

`SECURITY-3` lists the credentials the user supplies: the token of the cloud provider and the mail server. The API key
of OpenRouter is one too; an admin enters it in the settings, and the backend keeps it in its database.

Fix: name it in `SECURITY-3`.

## Could fix

### `RR-6` The API review refers to `API-5`, which went

The description of [api-review.md](api-review.md) says "API-1 to API-5"; `API-5` (authentication) went, `API-6` is
there.

Fix: "API-1 to API-4 and API-6".

### `RR-7` GROCERY.md is not like the other apps

`GROCERY-2` is a short note ("an input field ad the top", no full stop), not a rule in the words of the glossary.
GROCERY.md has no sections Data, API, Settings and External systems like HOSTING.md and REDIRECTS.md; `GROCERY-11` is
indented below `GROCERY-3` like a part of it, but has an id of its own, not `GROCERY-3.1` as `UX-4.1`.

Fix: write `GROCERY-2` again and give GROCERY.md the sections of the other apps.

### `RR-8` Health is an app and a maintenance page

[apps/INDEX.md](../architecture/apps/INDEX.md) lists Health in the table of the apps, `FRONTEND-1` lists it with the
settings as a maintenance page below the apps, and the text below the table names the settings as "no app" but not
the health.

Fix: one of the two, in the table and in `FRONTEND-1`.

### `RR-9` The access logs have no manager

`EXT-1` asks for one manager for each external system, the one place its clients are made. The client of victorialogs
is made in the routes (`SakApps.kt`, `VictoriaLogsClient(config.logs.victorialogsUrl)`), and the client of caddy in
`SakBackend.kt`. Both run on the same server, so they may not be external systems in the sense of `EXT-1`.

Fix: say in `EXT-1` whether the services on the server count, or give them a manager.
