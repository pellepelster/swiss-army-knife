---
title: "Review: Android"
linkTitle: "Android"
description: "The review of android/: the core every app shares, the build and the release, the grocery app and the notes app."
weight: 5
---

# Review: Android

**Created:** 2026-11-02

Scope: `android/` at commit `43083a1`: `core`, `testing` and `api` (the models generated from `openapi.json`), the
build and the release (gradle, manifests, `mise.toml`, the workflow), `grocery` and `notes`. Four reviews, one of each
part, each finding checked against the code; the schema gaps the generated web types showed are at the end.

What is good: the session token is encrypted with AES-GCM under a key of the keystore that does not leave it; release
builds allow https only; the `UpdateReceiver` and the FileProviders are not exported; an update is only a newer
release, and the client of a login link is a closed list on the backend and on the web page. The names of the apks
match from the build to the download page and the updater, and a tag can not publish an unsigned apk. The fixes of
the reviews of the grocery categories and of the bought items survived the move to `core`. The logic of both apps is
in models without Android, tested on the JVM against a fake backend; `Wav.kt` writes a correct header and stays under
the limits of the backend; the notes follow `UX-2`, `ANDROID-2` and `ANDROID-3`, and the AI is gated as on the web.

## Must fix

### `AN-1` A broken session file crashes the app on every start

`DataStoreSessionStore.kt:22` opens the DataStore without a `corruptionHandler`; a truncated `session.preferences_pb`
throws `CorruptionException` in `load()`, which `SakModel.start()` does not catch, on every launch, until the user
clears the data of the app.

Fix: `ReplaceFileCorruptionHandler { emptyPreferences() }`.

### `AN-2` A login link handled again throws a signed-in user back to the sign-in

`SakModel.signInWithLink` shows the sign-in with "not requested" when nothing is pending. A link tapped twice runs two
exchanges, the second gets 401 and shows the sign-in although the first signed in; an app opened again from Recents
gets the first VIEW intent again (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`), `MainActivity.signIn` of both apps handles
it, nothing is pending, and the signed-in user sees the sign-in.

Fix: ignore an intent launched from history and a link while signed in; one link at a time (a `Mutex`); the handling of
the intent into `core`, it is the same in both apps.

### `AN-3` A request of an old session changes a newer one

`requestLink` and `signInWithPassword` keep the spinner forever when "Other deployment" ran meanwhile; a password
sign-in that answers after `changeDeployment` stores a token without a deployment; a 401 of a request with token A
that answers after a sign-in with token B clears B.

Fix: `sessionEnded` clears only the token the request was made with; `changeDeployment` and the sign-out cancel the
requests in flight, or the answers check that their screen and deployment are still current.

### `AN-4` Notes: the saves of a note can land in the wrong order

Every change of a note (`setColor`, `addLabel`, `removeLabel`, `addPhoto`, the text) sends its own `PUT` of the whole
note (`NotesModel.saveNow`); two at a time can reach the backend in either order. The answer of the older one is
dropped (`versions`), so the phone shows the newer note while the backend keeps the older one, and the next load
takes the change back. Tap blue then green quickly, or add three labels in a row.

Fix: one save of a note at a time; a change while it runs marks the note and sends it once more after the answer.

### `AN-5` Notes: a load drops changes, notes created and notes deleted meanwhile

`NotesModel.load` keeps only the notes with text waiting (`pendingText`); a save in flight, a note just created and
one just deleted are not kept. A note created while the app loads after it came back to the front does not open; a
note deleted during a load comes back (404 when opened); a text whose save answers after a load is reset in the field,
the next key saves the old text.

Fix: what changed since the load started (text waiting, saves in flight, creates, deletes) stays, as the web does with
`changedWhileLoading`.

### `AN-6` Grocery: changes of the categories still queued when the settings close lose an icon or go

The queued changes read `settings` only when their turn comes (`currentCategory`, the order); `back()` and
`saveSettings()` close the settings first. A name typed and left with back is saved with `icon = null`: the icon of
the category is deleted on the backend; a move or an icon queued behind another change is dropped without a word.

Fix: a change takes what its request needs (the icon, the ids in their order) when it is queued; a test that renames a
category with an icon and goes back.

### `AN-7` Grocery: a second drag drops at the wrong place

`pointerInput(category.id)` starts its gesture once and keeps the `onDragEnd` of its first composition, with the order
of the categories of then; the rows are keyed (GC-1) and never start it again. After a first drag, a second one
computes its place from the old order: the row lands elsewhere, or nowhere.

Fix: `rememberUpdatedState` for `onDragEnd`, or the order from a state holder; a Compose test of two drags in a row.

## Should fix

### `AN-8` A link of someone else can sign a user in while a login waits

`PendingLogin` takes any link of the deployment within 15 minutes. `MainActivity` is exported: an app, or a page with
an `intent://` link, sends `…://signin?token=<a valid login token of the attacker>` while the victim waits for their
link, and the victim is signed in to the account of the attacker and adds notes and photos there.

Fix: a random value of the app in the login request, kept in the login token and checked on the exchange (sak-auth
`/link` and `/exchange`).

### `AN-9` The password of the admin is kept in the saved state

`SakScreens.kt:145` holds the password in `rememberSaveable`; Android writes it into the saved state of the activity,
in plain text on the disk after process death (data at rest). Fix: `remember`.

### `AN-10` The debug build of the notes can not reach a local backend

The configuration that allows http to `10.0.2.2` and `localhost` is only in `grocery/src/debug`; the notes debug
build refuses `http://10.0.2.2:8080`. Fix: the file and a debug app name into `core/src/debug`.

### `AN-11` A key of the keystore that broke is never made again

`KeystoreCipher.key()` returns the alias also when the key can no longer be used (after an update of Android on some
phones); every encrypt fails and every sign-in shows "password not checked". Fix: delete the alias and make a new key.

### `AN-12` The confirmation of an update does not show when the user left the app

`UpdateReceiver` starts the confirmation of the installer from a broadcast; when the user left the app during the
download, Android blocks it, and the model already ended its busy state without an error. Fix: hand the confirmation
to the activity, or a notification.

### `AN-13` The update connects to GitHub without saying so

The update downloads from `github.com`, which sees the address of the phone; the banner does not name GitHub (privacy
rule of AGENT.md). Fix: name GitHub on the banner or the button, or record why it needs no consent in SYSTEM.md.

### `AN-14` A transfer to a new phone copies the data of the apps

`allowBackup="false"` does not stop the transfer from device to device from Android 12 on; the session store (e-mail
address, deployment) moves along. Fix: `dataExtractionRules` that exclude everything, in the manifest of `core`.

### `AN-15` App code in core, the same code in both apps

`Stored.openList` is of the grocery lists only, and `write()` clears every key, so an app can keep none of its own;
the alias of the key is `sak-grocery-session` for both apps. The ViewModel wiring, `MainActivity.signIn`, the
manifest attributes and the build files of the apps (version, signing, build types, SDKs, dependencies that `core`
already gives) are copies. Fix: keys of the app in its own store; the copies into `core` and a convention plugin.

### `AN-16` The versionCode collides from 100 on

`major*10000 + minor*100 + patch` makes 0.1.100 the code of 0.2.0; Android refuses the later release as a downgrade.
Fix: `major*1_000_000 + minor*1_000 + patch`, and a build that fails above.

### `AN-17` The release does not check the key it is signed with

`apksigner verify --print-certs` checks that the apk is signed, not with which key; a changed secret publishes apks
that no installed app takes as an update. Fix: compare the SHA-256 of the certificate with one kept in `mise.toml`.

### `AN-18` The api module needs a JDK 17 nothing installs

`api/build.gradle.kts` sets `jvmToolchain(17)`; mise installs another JDK, there is no toolchain resolver. It builds
here and in CI only by chance. Fix: the target 17 like the other modules, without a toolchain.

### `AN-19` The release can not run again after a failure

`gh release create` fails when the release exists, `gh release upload` without `--clobber` when an apk exists; a
release that failed halfway can not be finished. Fix: create it when it is missing, upload with `--clobber`; the title
and the notes name the apps too.

### `AN-20` The quick release can tag a commit whose release build fails

The quick release runs only the unit tests of `android/`; the release build (and its lint) runs in CI after the tag.
Fix: also `android:release:build` before the tag, and `api:generate` next to `i18n:generate`.

### `AN-21` The caches of photos and recordings count entries, not bytes

Notes keep 64 files (a recording up to 9.6 MB), the grocery lists 60 photos; that can be hundreds of MB and end the
app. Fix: an `LruCache` with `sizeOf`, about 32 MB.

### `AN-22` Notes: a recording goes on in the background

Android records silence for an app in the background; a user who switches apps uploads up to 5 minutes of silence.
Fix: stop the recording, keeping what it has, when the app goes to the background.

### `AN-23` Notes: the recording bar can be out of sight

For a new note it is an item of the wall that scrolls away; it does not show on a note; a note opened during a
recording has no Stop, and every other record button is disabled. Fix: one bar at the root of the app, as the overlay
of the web.

### `AN-24` Notes: a change over a limit of the backend breaks every later save

The backend takes at most 20 photos, 20 recordings, labels of 64 and texts of 10000 characters; the app checks none,
and every save sends the whole note, so one change over a limit makes every later save of the note fail, its text
too. Fix: the limits in the app (disabled add, max lengths), and a change refused takes itself back.

### `AN-25` Notes: a photo or a recording goes with one tap

The × removes it and saves at once, without confirmation or undo (`UX-4`); the web offers undo. Fix: undo, or a
confirmation.

### `AN-26` Notes: a user who is no admin never gets the AI, and reads a wrong reason

`GET /settings` is for admins only; a user gets 403 and the app takes the AI as off, also when an admin turned it on,
and says "an admin turns it on". The web does the same. Fix: the backend answers the effective switch to every user
(e.g. `GET /notes/settings`), both clients read it.

### `AN-27` Notes: a photo is lost after Android ended the app behind the camera

The model starts again without the screen the photo is for; it is not attached, and the file of the camera (with its
EXIF, the place included) stays in the cache. Fix: keep the photo until the notes are loaded; always delete the file
of the camera.

### `AN-28` Notes: two recordings play at once, and play on

A recording tapped while another one is written to the cache plays next to it, the first player is never released;
playing goes on after the note closes, in the background and after the sign-out; `recording.wav` stays in the cache.
Fix: check again after the write; stop on leaving the note and on `ON_STOP`; delete the file on stop and sign-out.

### `AN-29` The last change can be lost when the app closes

The last save of a note text (notes) and a check that waits (grocery) run in `viewModelScope`, which ends with the
activity: back twice, or the app swiped away, cancels the request. Fix: send them on a scope that outlives the
activity (`NonCancellable`, the process).

### `AN-30` Notes: 29 texts are the same as on the web

`notes.android` repeats `create_note`, the colors, `labels_full`, the texts of the AI and ten errors of `notes.web`
word for word; `notes.common` is empty. Fix: move them to `notes.common`.

### `AN-31` Grocery: a dialog of a list that is gone shows again

`load()` keeps `renaming` and `deleting` when the open list goes, `viewing` is never cleared; the dialog shows again on
the next list, its save gives 404. Fix: clear them when the open list changes.

### `AN-32` Grocery: a second photo is dropped without a word

`photoRequest` returns while any photo is saved, the menu disables the photo of the busy item only; a photo of B
taken while one of A uploads is never attached. Fix: disable or queue, never drop.

### `AN-33` Grocery: the settings do not load their categories when the app comes back

Coming back to the front loads the lists and the items only; categories changed meanwhile give 400 on the next move.
Fix: also load the categories of the settings shown.

### `AN-34` Accessibility

The ⋮ of the item rows, of the list rows, of `AccountMenu` and of a note have no description; `Error()` and the undo
bar are no live regions, and the undo bar stays 1.5 seconds, too short for TalkBack; the actions Move up/Move down
are on a row TalkBack does not focus; the name field of a category and the photos of a note have no label. Fix:
descriptions, live regions, the recommended timeout of the accessibility manager, the actions on the drag handle.

### `AN-35` Grocery: no suggestions on Android

`GROCERY-11` is a rule of every client, the field of the app suggests nothing. Fix: the suggestions in the app, or
the rule says it is the web only.

### `AN-36` Reading a large photo can end the app

`PhotoScaling` decodes a photo of 12 MP whole (about 48 MB) before it scales it, and catches `Exception`, not
`OutOfMemoryError`. Fix: decode with a sample size close to the target, or `ImageDecoder` with a target size.

### `AN-37` The release apks are not minified

13.3 MB and 11.1 MB, every update downloads the whole apk. Fix: R8 and `isShrinkResources`, the release tried on a
phone before.

## Small items

- `AN-38` `SakApi.all()` pages until `total`, without a bound; a server that ignores `offset` makes it loop until the
  memory is full. A bound, and stop at a short page.
- `AN-39` No timeouts beyond the defaults of OkHttp: a server that answers slowly holds a request without end.
- `AN-40` The actions of the keyboard (Go, Send, Done) of the deployment and the sign-in ignore the busy state: two
  requests, which use up the limits of the logins.
- `AN-41` The login client is a string; a typo fails only at run time. The apps pass the generated enum.
- `AN-42` The sign-in has dead ends: no change of the address or new link after "link sent", no retry when the
  deployment did not answer.
- `AN-43` `apiUrl` refuses international domain names, and strips `sak.`/`api.` from a base domain that starts with
  them.
- `AN-44` The sign-out is local; a session token stays valid until it ends. Accept it in SYSTEM.md, or revoke it.
- `AN-45` A login link never opens a debug build (its package is `.debug`); say it in `android/AGENTS.md`.
- `AN-46` `signingOut()` of an app and `store.save` in `changeDeployment` throw into `viewModelScope` without a
  handler: the app ends.
- `AN-47` `integer` is `Int` in the generated models; a file of more than 2 GB fails the whole page.
- `AN-48` `english()` formats in the locale of the machine and drops a missing `strings.xml` of `core` without a word.
- `AN-49` Build: the version is checked in mise only (a tag `v1.0.0-rc1` builds versionCode 1); the release task can
  pick a stale unsigned apk; `setup-gradle` gets an input it does not know; the run tasks install on every device
  attached; run and install are copied for each app and install pulls the emulator; the list of apps is in five
  places; no monochrome layer of the icons; grocery lacks `adjustResize`; the alias of the key is `sak-grocery`; the
  generated sources are not known to the IDE before a build.
- `AN-50` openapi-generator 7.14.0 is old; a newer one, the models checked.
- `AN-51` Notes: an error stays after the next action; the label chips are hidden at 10, not disabled (`UX-8`); the
  timer of a recording starts again on rotation and the stop at 300 s comes without notice; mirrored EXIF
  orientations are ignored; a recording peaks at about 35 MB in memory; a delete shows no progress; every return to
  the front loads every page; a label on a card does not filter, "All" has no count, a note can not be pulled to
  reload.
- `AN-52` Grocery: a change event during the eye can show a checked item as to buy for a moment; a stream that opens
  and closes at once loads everything every second; a photo after an ended session waits for any later sign-in; the
  back arrow of the settings says "All lists" when it leads to the list; a category without an icon shows "＋"
  (`UX-2`); unused imports and stale comments from the move; `GroceryApp.kt` split into the home, the list, the
  settings and the photos.
- `AN-53` Schema of the backend (found by the generated web types): `userMode`, the state of the health and the color
  of a note are strings, not enums; the default of a setting (`JsonPrimitive`) is a string in the schema, can be a
  boolean; `requires` of a setting is sent as `null`, its schema is not nullable.
- `AN-54` Tests that are missing: `SakModel` is tested only through the grocery lists (paging over pages, errors that
  are no JSON, links twice, changes of the deployment during a request); the order of the saves of a note, a load
  during a save, a create or a delete; failed uploads, a 401 during a save or a recording; the category changes of
  `AN-6` and the drags of `AN-7`; a second photo; a dialog of a list that goes.
