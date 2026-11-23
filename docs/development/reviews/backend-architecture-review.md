---
title: "Review: architecture of the backend"
linkTitle: "Backend architecture"
description: "The review of the architecture of the backend (its modules, layers, routes, services and repositories) for inconsistencies, duplications and code smells."
weight: 12
---

# Review: architecture of the backend

**Created:** 2026-11-22

**Checked again:** 2026-11-22, after the fixes of `BA-1` to `BA-20`, `BA-22`, `BA-23` and `BA-26` to `BA-30`, which
are in the history of git; `BA-21`, `BA-24` and `BA-25` list what is left of them. Of `BA-22` one copy stays on
purpose: the notes and the grocery lists each have a route `GET /settings` of their own, an app owns its settings
endpoint.

Scope: the Kotlin modules of the backend (`sak-backend`, `sak-model`, `sak-storage`, `sak-s3`, `sak-api`, `sak-auth`,
`sak-logs`, `sak-dns`, `sak-ingress`, `sak-openrouter`, `sak-config`), about 19 000 lines, against `LAYER-1`, `API-1`
to `API-6`, `MODEL-1` to `MODEL-8` and `EXT-1` of [SYSTEM.md](../architecture/SYSTEM.md) and the rules of
[the components](../architecture/components/INDEX.md). The question: where the same thing is done in different ways,
where code is copied, and where the code does not keep its layers and modules. Security, the schema and missing tests
are for the other reviews.

How sure a finding is: every finding was **read** in the code by a reviewer; the ones marked **checked** were read
again by the author of this review. Nothing was run for this review.

In the evidence, `backend/` is `sak-backend/src/main/kotlin/io/pelle/sak/backend/` and `model/` is
`sak-model/src/main/kotlin/io/pelle/sak/model/`.

What is good: the modules depend in one direction only, with no cycle; `sak-storage`, `sak-config`, `sak-ingress` and
`sak-model` know no other module, `sak-s3` only `sak-storage`. No code outside `sak-model` touches jOOQ. Every
repository but one is made by its service only, and every service has the same two constructors. Every delete is a soft
delete, every time comes from the clock of the repository, no update sets an owner. Every list of the api is a page,
every time goes through `apiTime()`, every create answers 201. Caddy, victorialogs and OpenRouter each have one
manager (`EXT-1`). All binary data goes through `BinaryStorage`, and files, buckets and repositories of restic are
found by their resource id only.

## Could fix: duplications

### `BA-21` The lists of the notes are not on `ListTable`

The todo lists and the grocery lists share the work on a list (`Lists`), the checks of a name and an icon, the check
of who sees a list, and "the first one of an owner" (`firstOfOwner`, which the first repository of restic uses too).
Their routes and their request and response classes stay two on purpose: the api of an app is its own. One copy is
left: `NoteRepository` works on the table of the lists of the notes itself. The notes do not show their lists in the
api (`NOTES-1`), so they would use nothing of `ListTable` but the first list.

Fix: when the notes get lists in their api, their lists go on `ListTable`.

## Could fix: inconsistencies

### `BA-24` One component has several names

- Static hosting is `/static-hosting`, the package `hosting`, the tag `s3` and `S3BucketService`; the redirects have
  the tag and the model package `ingress`; `/deployment` has the package and the tag `config` and the only file
  named `Sak…Routes.kt`. The tags stay on purpose: they are a part of the api document.
- The entity of the notes is in the service file with a `NoteRow` in the model; the other apps keep theirs in
  `sak-model`. It goes with the lists of the notes (`BA-21`).
- Methods: `byId`, `page`, `create` (notes, labels) and `item`, `items`, `addItem` (todo, groceries); `of(owner)`
  and `all()`.

Fix: only with another reason to change that code; a new name of a package or a method touches every caller.

### `BA-25` No rule says which verb a change has and what it answers

The routes agree now: a checked item of a grocery list is answered like a done task, the delete of a photo answers
204 like every delete, the photo of an item is read below `…/photo/`, and the items of a photo are discovered at
`…/files/{id}/discover/items` as its text is. The paths of the apps are written once, and the api has its plugins
once. Nothing tells the next route what to do.

Fix: a rule for the verbs and the answers, e.g. a change answers the thing, a delete answers 204.

## Not checked

- The tests, the frontend, the Android apps, the desktop apps and the roles of ansible.
- The line numbers are of the working tree of 2026-11-22.
