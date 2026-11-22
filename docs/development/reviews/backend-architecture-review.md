---
title: "Review: architecture of the backend"
linkTitle: "Backend architecture"
description: "The review of the architecture of the backend (its modules, layers, routes, services and repositories) for inconsistencies, duplications and code smells."
weight: 12
---

# Review: architecture of the backend

**Created:** 2026-11-22

**Checked again:** 2026-11-22, after the fixes of `BA-1`, `BA-2`, `BA-3`, `BA-6`, `BA-9`, `BA-10`, `BA-11`, `BA-12`,
`BA-13`, `BA-14`, `BA-19`, `BA-20`, `BA-22` and `BA-30`, and of `BA-4`, which are in the history of git; `BA-7` and
`BA-23` list what is left of them. Of `BA-22` one copy stays on purpose: the notes and the grocery lists each have a
route `GET /settings` of their own, an app owns its settings endpoint.

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

## Should fix: the code breaks a rule

### `BA-5` Routes and the start do the work of a service (`LAYER-1`)

- `backend/users/SakUsers.kt:91-110`: the route of the app link normalizes the address, looks for the user, sends the
  mail and logs.
- `backend/grocery/SakGrocery.kt:652-675`: the route of a photo combines `GroceryService` and `FileService` and
  chooses the content type; `Route.grocery` takes the `FileService` only for it.
- Routes take a store next to their service: `ResticStore` for the size of a repository
  (`backend/restic/SakRestic.kt:70-73,206,225`), `BucketStatisticsCache` (`backend/hosting/SakBuckets.kt:52,276-284`).
- `backend/SakBackend.kt` warms the statistics of the buckets and starts the job of the unfinished uploads
  (`hosting/StaleUploads.kt`); no class of `hosting/` owns the two.

Fix: `UserService.sendAppLink`, `GroceryService.photoContent`, a size function on the two services, and a class of
`hosting/` that starts its own jobs.

### `BA-7` The access key id of a deleted bucket stays taken (`MODEL-3`)

`access_key_id` of `s3_buckets` is unique over the deleted rows too (`0001-initial.yaml:25-30`), though `MODEL-3` keeps
only the name of a deleted bucket taken. No request can see it: a new key pair that meets one is drawn again
(`S3BucketService`, one in 2^100).

Fix: a partial index over the live rows, which in sqlite rebuilds the table the domains and the redirects refer to.

### `BA-8` The compiler does not keep jOOQ in `sak-model` (`MODEL-2`, checked)

`sak-model/build.gradle.kts:15` has jOOQ as an `api` dependency, and `model/lists/ListTable.kt:59-63` is a public
class with a public table and public functions that take a `DSLContext`. Only `SakDatabase.dsl` is internal. No code
outside `sak-model` uses jOOQ today.

Fix: `implementation(libs.jooq)`, and `ListTable` internal.

## Could fix: modules

### `BA-15` The guard of the S3 api guards restic, and nothing guards the files

`WriteGuard` and its two exceptions are in `sak-s3` ("the room a write to a bucket may take"). `ResticStore` uses
them (`backend/restic/ResticStore.kt:58,121,125`, `backend/restic/SakRestic.kt:45-46`) with the setting
`s3_min_free_bytes` (`backend/SakBackend.kt`, `resticStore`), and its largest file is cut at the largest S3 object.
`FileService.store` writes with no look at the free bytes. Three stores on one volume guard it in two ways.

Fix: `WriteGuard` and the exceptions move to `sak-storage`, the setting is named after the volume.

### `BA-16` What a module holds is more than the parts say

- `sak-auth` ("the e-mail login, the session tokens, the signed requests of sakadmin, the roles") holds
  `RateLimiter`, which also limits OpenRouter and restic, `clientNetwork`, which restic uses, and `MailSender`, which
  the mail of the app link uses.
- `sak-api` ("the error answer, pagination, health") holds the texts (`Texts.kt`) and the helpers of the logs
  (`LogAddress.kt`). `sak-dns` so gets Ktor for `Health`, `Text` and `logCause`.

Fix: `RateLimiter` and `clientNetwork` move next to `logClient`; the row of `sak-api` in the parts names what it has.

### `BA-17` The backend needs the class behind the interface of the S3 storage

`usage`, `removeStaleUploads` and `objectsDirectory` are on `BinaryStorageS3Backend` only, not on `S3StorageBackend`.
The backend so holds the class (`backend/SakBackend.kt`, `BucketStatisticsCache`, `backend/SakCaddy.kt`) next to
`InvalidatingS3StorageBackend`, which wraps the interface. The routes of `sak-s3` decode the token of a listing with
a function of the class.

Fix: a second small interface for the three functions.

### `BA-18` More is public and more is declared than is used

- `sak-s3`: nothing of `api/` and `auth/` but `Route.s3` and the access callback is used outside the module, all of
  it is public.
- `sak-logs`: `accessLogs` takes two parts of the `AccessLogsManager` (`backend/SakApps.kt:210-214`); `quoted`,
  `streamFilter`, `TimeSeriesGrid`, `StatsSeries` and `UserAgentClassifier` are public and used inside only.
- `sak-auth`, `sak-logs` and `sak-backend` declare `ktor-server-content-negotiation`; only `sak-api` installs it.

Fix: `internal` for what one module uses; the three dependencies go.

## Could fix: duplications

### `BA-21` The lists of the todo app and of the groceries are copies

- Routes: `GET/POST /lists` and `GET/PUT/DELETE /lists/{id}` with their descriptions, about 100 lines each
  (`backend/todo/SakTodo.kt:62-163`, `backend/grocery/SakGrocery.kt:196-300`), and the request and response classes
  of a list with the same fields.
- Services: about 40 lines each (`TodoService.kt:61-113`, `GroceryService.kt:119-175`).
- The check of a name is written four times, the one of an icon twice (`backend/lists/Lists.kt:81-89`,
  `TodoService.kt:135-139`, `GroceryService.kt:358-366,386-390`).
- `GroceryRepository` writes "the user sees the list" itself in six places; `ListTable.sees` has it, and
  `TodoRepository` uses it.
- "The first one of an owner" is written three times (`ListTable.insertFirst`, `NoteRepository.ensureList`,
  `ResticRepositoryRepository.insertFirst`); the lists of the notes are the only ones not on `ListTable`.

Fix: the routes of a list and one request and response class in `lists/`; the checks in `Lists.kt`; `lists.sees` in
`GroceryRepository`.

## Could fix: inconsistencies

### `BA-23` A service and a repository answer an outcome in many shapes

Every result type is named `<Entity><Verb>Result` now. What is left:

- Services: next to the sealed results a `Boolean`, `null`, an `Int?` no route reads, and an exception for a case of
  the domain (`S3BucketService`, `replaceKeyPair`). Some results hold the finished `ErrorMessage`s, for others the
  route builds the text (the notes, the labels); "values or errors" has four forms (`Lists.kt`, `GroceryService`,
  `TodoService`, `NoteService`).
- Repositories: `null`, a sealed result, or a `Boolean` that means "not there" and "taken" at once
  (`S3BucketRepository.replaceKeyPair`, which makes the service read again).
- A delete has a result type of its own in each app (`NoteDeleteResult`, `LabelDeleteResult`, `ListDeleteResult`).

Fix: a sealed result with the errors in it wherever there is more than one way to fail, and one type for a delete.

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
  rely on a route next to them.

Fix: one line in INDEX.md for the verbs and the answers; `installApi()` once for `/api/v1` and once for the well-known
path.

## Could fix: smells

### `BA-26` The start builds things twice and hands 20 services over by their position

`rolesOf` is built in `backend/SakBackend.kt` and in `backend/SakApps.kt:155`, `loginApp(config)` three times in
`SakApps.kt`, the limiter of OpenRouter in `SakApps.kt` and again as the default of two route functions.
`SakServices` has 20 fields, set by position; next to services it holds a cache, a store, a manager and a storage.

Fix: build each once and name the arguments.

### `BA-27` Classes and functions that do too much

- `backend/files/FileService.kt`: the service, and in its companion the checks of a recording, the decoding and
  scaling of an image, the drawing of a waveform and the type of a file (250 lines).
- `backend/settings/Settings.kt`: `SettingService`, `SettingsService`, `StoredSettingsService` and `SettingsRegistry`
  with ten more types in one file; two names differ by one letter.
- `listObjects` of `BinaryStorageS3Backend` (69 lines), the 12 catches of `S3Routes.kt:112-147`.
- `NoteService` writes to the database in its constructor: it removes the links of deleted labels, because a delete
  of a label and the removal of its links are two transactions (`backend/labels/LabelService.kt:103-110`).

### `BA-28` The groceries: the service repairs what the repository answers

- `checkItem`, `deleteItem` and `setPhoto` answer a `Boolean` or a `null` with two meanings, so `GroceryService`
  reads the item before or after the write (`GroceryService.kt:226-230,287-298,308-309`). The todo lists answer the
  row of the update.
- The category of an item is checked in the service and again in the repository, which drops an unknown one in two
  places and refuses it in a third.
- The values of an item pass three classes with the same fields.
- The order of the items is made in the service with a second read; the todo lists order in sql.
- The repository reads the table of the files and checks that a photo is an image
  (`model/grocery/GroceryRepository.kt:493-536`); for the notes the service does it through `FileService`.

Fix: the repository answers the item and a result with the cases; `GroceryService` asks `FileService` for the photo.

### `BA-29` Code nobody calls, and comments that say another thing

- No caller outside the tests: `OpenRouterManager.enabled()`, `SettingService.delete`; `timesUsed` and `lastUsedAt`
  of a suggestion are filled and never read (checked).
- `User.updatedAt` and `SettingRow.updatedAt` are always null; `redirects.id` is never read.
- The descriptions of `PUT /lists/{id}` say every user who sees a list renames it; the code and `LIST-2` say the
  owner.
- `backend/SakApps.kt:94` says `<root_domain>` and passes the base domain.
- `SakBuckets.kt`, `SakRedirects.kt` and `SakFiles.kt` import classes of their own package.

## Not checked

- The tests, the frontend, the Android apps, the desktop apps and the roles of ansible.
- Whether a dependency can go (`BA-18`) was not tried with a build.
- The line numbers are of the working tree of 2026-11-22.
