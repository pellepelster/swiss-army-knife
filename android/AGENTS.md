# Android apps

The android apps of SAK, one gradle build with a module for each app on top of `core`. What all apps share is in
[apps/INDEX.md](../docs/development/architecture/apps/INDEX.md) and the system architecture; the look is in
[UI-UX.md](../docs/development/UI-UX.md), "All apps" and "Android app" (`ANDROID-*`). Each app's `AGENTS.md` links
its own architecture document.

- **Modules.**
  - `core` (library, `io.pelle.sak.core`): what every app needs. The deployment and the sign-in by link or password
    (`SakModel`, the screens `Screen.Deployment` and `Screen.SignIn`, the composables `DeploymentScreen` and
    `SignInScreen`), the session kept encrypted (`session/`), the base of the api (`SakApi`, `sakClient`,
    `ApiException`), the update check (`update/`, `AndroidUpdater` takes the name of the app of its apk), the
    theme (`SakTheme`), the parts of a screen (`Page`, `Form`, `Error`, `AccountMenu`, `UpdateBanner`,
    `ConfirmDelete`) and the texts of no app.
  - `testing` (library): what the tests of every app share, a fake backend (`FakeBackend`, `page()`) and the
    english texts (`english()`), on the jvm.
  - one module for each app, e.g. `grocery` (`io.pelle.sak.grocery`): its model extends `SakModel` (its screen
    once the user is signed in is an `AppScreen`), its api extends `SakApi`, its activity takes the login links of
    its own scheme. An app names itself to the backend when it asks for a login link (`SakApi.loginClient`, e.g.
    `grocery-android`), so the signin page of the web app hands the link on to that app only.
- **Build.** `mise run android:build`, `android:test` (all modules), `<app>:android:run` (emulator) and
  `<app>:android:install` (the phone attached by usb, `ANDROID_SERIAL` names one of several). The sdk is installed by
  `android:sdk`, without the emulator by `android:sdk:build`.
- **Release.** `android:release:build` writes `build/sak-<app>-<VERSION>.apk` of each app (`ANDROID_APPS`) and its
  `.sha256`; the versionCode comes from the version (1.4.2 is 10402, `latest` is 1). The apks are signed with the
  keystore of `ANDROID_KEYSTORE` (base64) and its passwords, the secrets of the workflow (`github:secrets:update`,
  from `pass` `sak/android/signing-key` and `sak/android/signing-key-password`); a release is never unsigned. The job
  `android` builds them on every build, signed for a tag only, as the artifact `android-apks`; the job `release`
  adds them to the github release of the tag (`android:release`). The key is the identity of an app: an update with
  another key does not install over it.
- **A new app** is a module in `settings.gradle.kts`, its name in `ANDROID_APPS` of `android:release:build` and
  `android:release` in `mise.toml`, its resources in `ANDROID_APPS` of `sak-i18n` (`Generator.kt`), and its client
  in `LoginClient` of `sak-auth` with its package in the signin page of the web app.
- **The api.** The response classes in `core/api/ApiModels.kt` and in an app's `api/ApiModels.kt` are copies of the
  ones of `sak-backend`, with the fields the apps use only; change them with the api.
- **The logic** is in the model of each app (and `SakModel`), without android, tested on the jvm against the fake
  backend.
- **Texts.** Every text the user reads is in `i18n/texts.yml` at the root of the repository: `android` and `common`
  for texts of no app go to `core`, `<app>.android` and `<app>.common` to the module of the app, e.g.
  `grocery.form.save` is `R.string.grocery_form_save` of `io.pelle.sak.grocery.R`, `shared.delete` is
  `R.string.shared_delete` of `io.pelle.sak.core.R`. `mise run i18n:generate` writes them into `res/values/strings.xml`
  (english) and `res/values-de/strings.xml` (german) of each module, which are not edited; sak-i18n refuses a text
  without its german one. The models have no android: they hold a `UiText`, a string resource with its arguments or
  a text the backend sent, and the ui reads it. The api sends the languages of the phone (`Accept-Language`), so the
  backend answers its texts in the same language. The tests read the english texts (`english()`), the build of an
  app names its package to them (`sak.app.package`).
