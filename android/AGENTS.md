# Android apps

The android apps of SAK, one gradle build with a module for each app on top of `core`. What all apps share is in
[components/INDEX.md](../docs/development/architecture/components/INDEX.md) and the system architecture; the look is in
[UI-UX.md](../docs/development/ui-ux/UI-UX.md) (all apps) and [UI-UX-ANDROID.md](../docs/development/ui-ux/UI-UX-ANDROID.md)
(`ANDROID-*`). Each app's `AGENTS.md` links its own architecture document.

- **Modules.**
  - `api` (kotlin library, no android, `io.pelle.sak.api.model`): the bodies of the api, generated at build time
    from `openapi.json` at the root of the repository (openapi-generator, kotlinx.serialization data classes), the
    schemas the apps use listed in `api/build.gradle.kts`. `core` passes it on to every app.
  - `core` (library, `io.pelle.sak.core`): what every app needs. The deployment and the sign-in by link or password
    (`SakModel`, the screens `Screen.Deployment` and `Screen.SignIn`, the composables `DeploymentScreen` and
    `SignInScreen`), the session kept encrypted (`session/`), what every model takes besides its api
    (`SakSetup`), the connect by the QR code of the web app (`qr/`: `QrConnect`, `SakModel.qr`, which calls
    back into `SakModel` for the switch of the session), the base of the api (`SakApi`, `sakClient`,
    `ApiException`), the update (`update/`: `UpdateOffer`, `SakModel.updates`, with the check of the start
    and of the menu, UX-13; `AndroidUpdater` takes the name of the app of its apk), the
    theme (`SakTheme`), the parts of a screen (`Page`, whose `onBack` draws the back arrow and takes the back
    of the system, `Form`, `Error`, `AccountMenu`, `UpdateBanner`, `ConfirmDelete`, `MenuEntry` and the red
    `DeleteMenuEntry`), the photos (`ui/Photos.kt`: `rememberPhotoCapture`, the camera or the picker for a
    target of the app, kept over a rotation and the end of the process, with the camera file and its
    `FileProvider` of the manifest of core; `PhotoScaling` and its `read`; `RemoteImage`; `PhotoViewer`, a
    photo in full), the cache of the photos and files fetched (`ByteCache`), a call whose failure is no
    error (`attempt`), a change that waits for its undo (`UndoWindow`, made by `SakModel.undoWindow`, which
    sends it when the app goes to the background and before the sign-out and drops it when the session ends)
    and the texts of no app. Every end of a session (another deployment, the sign-out, its end, the switch
    by a QR code) goes through one sequence of `SakModel` (`endSession`): the epoch, the store, the new
    screen, then the hook `sessionGone()` of the app, always last. `SakModel.foreground()` and `background()` say when the app is seen (from
    `SakActivity`, not on a rotation). What the list-based apps (grocery, todo) share is in `lists/` and
    `ui/Lists.kt`: `ListsModel` (on top of `SakModel`: the lists and the list open with the counter of the
    list shown, `show` and `load`, a list that went leads to the home, an entry added to the list open, the
    settings of a list (`ListFields`) created, saved and deleted, a list deleted from the home, and the
    `ChangeStream` while the app is seen, with the hooks `listChanged` and `reconnected`) over `ListsApi` (the
    list calls each app's api implements), and the screens of the lists. An app makes its screen of the lists
    (`appScreen`), resets it for the list shown (`opened`) and loads the entries of the list open (`loaded`).
  - `testing` (library): what the tests of every app share, a fake backend (`FakeBackend`, `page()`,
    `answerOn()`, the answer of `/info` of many users by default), the english texts (`english()`), the
    `SakSetup` of a model in a test (`sakSetup()`, with an updater that fails when it is called), the fixtures
    (`Fixtures.kt`: the address `ADDRESS`, `signedIn()` and `signedOut()`, the answers of core built of the
    generated classes and encoded with `ApiJson`) and, on robolectric, the activities, `shows()`, `settle()` and
    the screenshots. Its tests (`SakModelTest`, `ListsModelTest`) test `SakModel` and `ListsModel` with an app as
    small as possible; the tests of an app test only what the app adds. Each app has one file of fixtures
    (e.g. `GroceryFixtures.kt`: `list()`, `item()` …) of the generated classes, for its model, rotation and
    screenshot tests.
  - one module for each app, e.g. `grocery` (`io.pelle.sak.grocery`): its model extends `SakModel`, of a list-based
    app `ListsModel` (its screen once the user is signed in is an `AppScreen`, of a list-based app a
    `ListsAppScreen`), its api extends `SakApi` (and implements `ListsApi`), its activity extends `SakActivity`
    (which takes the login links of its scheme, once, and tells the model when the app is seen) and its view
    model `SakViewModel` (the `SakSetup` of its model, made once: the store of the session, the scope of the
    model and the scope of the process for the lasting work, the updater; the client of the process, whether
    the activity stops for a rotation). An app names itself to the backend when it asks for a login link
    (`LoginRequest.Client`, e.g. `GROCERY_ANDROID`), so the signin page of the web app hands the link on to that
    app only; the request carries a nonce the exchange needs. What an app keeps with the session goes into
    `Stored.app` (its own names, e.g. the list open last), work that must not end with the screen (the last save
    when the app closes) into `launchLasting`, a request of an upload or a download sets `longTransfer()`.
- **Build.** `mise run android:build`, `android:test` (all modules), `android:run <app>` (the emulator only, also
  with a phone attached) and `android:install <app>` (the phone attached by usb, `ANDROID_SERIAL` names one of
  several). The sdk is installed by `android:sdk`, without the emulator (enough for a build and an install) by
  `android:sdk:build`.
- **Conventions.** What every module shares is in the convention plugins of `build-logic` (an included build;
  the root build brings the android plugin and the compose plugin, the convention plugins apply them by id):
  `sak.android.library` (`core`, `testing`: the sdks of `SakSdk`, java 17, the test property `sak.app.package`,
  compose with `sakLibrary { compose() }`) and `sak.android.app` (the apps: the sdks, java 17, compose, the version
  of SAK, the signing, the build types, the flavors, r8, the test property `sak.app.package`, and the dependencies
  every app has: `:core`, `:testing` for the tests, the tooling and the test manifest of compose for debug). The
  build file of an app has only its namespace, its applicationId and what is its own; ktor and the api come with
  `core`. The rules of r8 for every app are in `proguard-rules.pro`.
- **Version.** `VERSION` is `latest` (every build but a release, versionCode 1) or a release `x.y.z`, whose
  versionCode is `x*1_000_000 + y*1_000 + z` (1.4.2 is 1004002); any other `VERSION`, or a minor or patch of 1000
  or more, fails the build (`SakVersion` of `build-logic`).
- **Distributions.** Every app has the flavors `github` and `play` (dimension `distribution`). `github` is the apk of
  the github release, which updates itself (`AndroidUpdater`). `play` is the bundle for google play, which updates
  the app and forbids an app to install itself: `BuildConfig.SELF_UPDATE` is false, so `SakViewModel` has no updater
  and the model offers no update and no "Check for updates" (`SakModel.updates.enabled`), and `play/AndroidManifest.xml`
  removes `REQUEST_INSTALL_PACKAGES` and the receiver of the update. The tasks of mise run, install and test
  `github`; `android:build` builds both.
- **Google Play.** `android:play:build` writes `build/sak-<app>-<VERSION>.aab` of each app and
  `build/sak-<app>-<VERSION>-mapping.txt`, the map of r8 for the play console, signed and checked as the apks of a
  release. The key is the app signing key of play (play app signing), so an apk of github installs over an app of
  play. The package of an app in the play console is its applicationId, e.g. `io.pelle.sak.notes`. The job
  `sak-android-apps` builds them as the artifact `android-play`; the job `release` adds the bundles and the maps to the
  github release of the tag (`android:release`), they are uploaded to the play console by hand.
- **Release.** `android:release:build` writes `build/sak-<app>-<VERSION>.apk` of each app and its `.sha256`. The apks
  are signed with the keystore of `ANDROID_KEYSTORE` (base64) and its passwords, the secrets of the workflow
  (`github:secrets:update`, from `pass` `sak/android/signing-key` and `sak/android/signing-key-password`; the alias
  of the key is `sak-grocery`, from when grocery was the only app); a release is never unsigned, the build of a
  version without the keystore fails. A signed apk must carry the certificate `ANDROID_CERT_SHA256` of
  `android:release:build`, the key every installed app was signed with; another key fails the release, an update with
  it would install over no app. The release apks are shrunk by r8: try a release build on a phone after a change of
  a dependency or of `proguard-rules.pro`. The job `sak-android-apps` builds them on every build, signed for a tag only, as the
  artifact `android-apks`; the job `release` adds them to the github release of the tag (`android:release`, a file
  already there is replaced, so the job runs again after a failure).
- **Debug builds** are the package `<app>.debug`, installed next to the release. A login link never opens them: the
  signin page of the web app hands the link to the package of the release (`signin.ts`), so a debug build signs in
  with the password of the admin.
- **A new app** is a line in `apps.txt` (which `settings.gradle.kts`, `mise.toml` and `sak-i18n` read), its client
  in `LoginClient` of `sak-auth` with its package in the signin page of the web app (`signin.ts`).
- **The api.** The request and response classes are generated from `openapi.json` (module `api`), never written or
  copied by hand; only the generic page (`PageResponse`, whose schema is inline for each list) is in
  `core/api/ApiModels.kt`. After a change of the api, `mise run api:generate` writes `openapi.json` from the routes
  of `sak-backend` (a test of the backend fails while it is not current); a schema an app starts to use is added to
  the list in `api/build.gradle.kts`. A nullable field without a value is left out of a request, the backend takes
  a missing nullable field as null; the fake backends of the tests answer every field the schema requires.
- **The logic** is in the model of each app (and `SakModel`), without android, tested on the jvm against the fake
  backend.
- **Icons.** The icons are in `sak-theme/images/android/`, not in a module: `icons/` (Font Awesome as vector
  drawables, each once, `R.drawable.ic_<name>` of `core` and of every app) and `<app>/` (the launcher icon of the
  app); `core/build.gradle.kts` and `sak.android.app` add them as res directories. A new icon goes there too.
- **Texts.** Every text the user reads is in `i18n/texts.yml` at the root of the repository: `android` and `common`
  for texts of no app go to `core`, `<app>.android` and `<app>.common` to the module of the app, e.g.
  `grocery.form.save` is `R.string.grocery_form_save` of `io.pelle.sak.grocery.R`, `shared.delete` is
  `R.string.shared_delete` of `io.pelle.sak.core.R`. `mise run i18n:generate` writes them into `res/values/strings.xml`
  (english) and `res/values-de/strings.xml` (german) of each module, which are not edited; sak-i18n refuses a text
  without its german one. The models have no android: they hold a `UiText`, a string resource with its arguments or
  a text the backend sent, and the ui reads it. The api sends the languages of the phone (`Accept-Language`), so the
  backend answers its texts in the same language. The tests read the english texts (`english()`), the build of an
  app names its package to them (`sak.app.package`).
