---
title: "Review: architecture of the backend"
linkTitle: "Backend architecture"
description: "The review of the architecture of the backend (its modules, layers, routes, services and repositories) for inconsistencies, duplications and code smells."
weight: 12
---

# Review: architecture of the backend

**Created:** 2026-11-22

**Checked again:** 2026-11-22, after the fixes of `BA-1` to `BA-16`, `BA-18` to `BA-20`, `BA-22`, `BA-26`, `BA-27` to
`BA-30`, which are in the history of git; `BA-21` and `BA-23` list what is left of them. Of `BA-22` one copy stays on
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

## Could fix: modules

### `BA-17` The backend needs the class behind the interface of the S3 storage

`usage`, `removeStaleUploads` and `objectsDirectory` are on `BinaryStorageS3Backend` only, not on `S3StorageBackend`.
The backend so holds the class (`backend/SakBackend.kt`, `BucketStatisticsCache`, `backend/SakCaddy.kt`) next to
`InvalidatingS3StorageBackend`, which wraps the interface. The routes of `sak-s3` decode the token of a listing with
a function of the class.

Fix: a second small interface for the three functions.

## Could fix: duplications

### `BA-21` "The first one of an owner" is written three times

`ListTable.insertFirst`, `NoteRepository.ensureList` and `ResticRepositoryRepository.insertFirst` each write "the
first one of an owner who never had one". The lists of the notes are the only lists not on `ListTable`.

The other copies of the lists are gone: the todo lists and the grocery lists share the work on a list (`Lists`), the
checks of a name and an icon, and the check of who sees a list. Their routes and their request and response classes
stay two on purpose: the api of an app is its own.

Fix: the lists of the notes on `ListTable`; one function for the first one of an owner.

## Could fix: inconsistencies

### `BA-23` A repository answers an outcome in several shapes

The services answer in one form now: a sealed result named `<Entity><Verb>Result`, a validation failure with its
`ErrorMessage`s, one `DeleteResult` for every delete and purge. What is left is in the repositories: a failure is
`null`, a sealed result or a `Boolean`, by the repository (`GroceryRepository.deleteItem` and `checkItem` answer a
`Boolean`, `TodoRepository.deleteItem` the row, `NoteRepository.delete` a `Boolean`). `GroceryService.checkItem` passes
its `Boolean` on.

Fix: a repository answers the row for a change, a sealed result where there is more than one way to fail.

### `BA-24` Where a thing lives and what it is called

- The texts of an app are in its route file (hosting, redirects, files, notes, labels) or in its service file (todo,
  groceries, restic, users, settings); the health of hosting so depends on a route file.
- The entity of the notes is in the service file with a `NoteRow` in the model; the other apps keep theirs in
  `sak-model`, some in a file of their own, most in the file of the repository.
- One component has several names: `/static-hosting`, the package `hosting`, the tag `s3`, `S3BucketService`;
  `/redirects` with the tag and the model package `ingress`; `/deployment` with the package and the tag `config` and
  the only file named `Sak…Routes.kt`.
- `backend/SakCaddy.kt` also holds the storage of the buckets and constants of the S3 api and of restic. One data
  directory is a property of `SakConfig`, three are functions of `SakBackend.kt`.
- Methods: `byId`, `page`, `create` (notes, labels) and `item`, `items`, `addItem` (todo, groceries); `of(owner)`
  and `all()`; `RedirectRepository.byId` takes a domain.

Fix: one file `<App>Texts.kt` for each app, the tags named like the paths, the directories in `SakConfig`.

### `BA-25` The same change has different verbs and answers

- Done: `PUT /todo/items/{id}/done` with a body answers the item; `POST /grocery/items/{id}/check` without a body
  answers 204.
- `DELETE /grocery/items/{id}/photo` answers 200 and the item, every other delete 204.
- `…/items/{id}/photo` and `…/items/{id}/photos/{fileId}`; `/grocery/discover/{id}` and `/files/{id}/discover/text`.
- The paths of the apps are written as text in `backend/SakApps.kt`, again in `backend/files/SakFiles.kt:45` and in
  the descriptions of `sak-auth`, the notes and the groceries.
- `installApi()` is called in 20 route functions. `backend/info/SakWellKnown.kt:27` calls it on the root route, so
  the plugins of the api also cover the web app (checked); `servedFiles` and `listChanges` call it not at all and
  rely on a route next to them, which `API-1` does not allow: every route has `installApi()`.

Fix: one line in INDEX.md for the verbs and the answers; `installApi()` once for `/api/v1` and once for the well-known
path.

## Not checked

- The tests, the frontend, the Android apps, the desktop apps and the roles of ansible.
- The line numbers are of the working tree of 2026-11-22.
