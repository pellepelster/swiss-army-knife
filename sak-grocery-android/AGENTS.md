# Grocery Android app

How this app is built is in [GROCERY.md](../docs/development/architecture/apps/GROCERY.md), which adds to
[apps/INDEX.md](../docs/development/architecture/apps/INDEX.md) and to the system architecture; its look is in
[UI-UX.md](../docs/development/UI-UX.md), "All apps" and "Android app" (`ANDROID-*`).
Every change here keeps to them; a change that breaks a rule of the app changes GROCERY.md in the same commit.

- A gradle build of its own with its own wrapper: `mise run grocery:android:build`, `grocery:android:test`,
  `grocery:android:run` (emulator), `grocery:android:install` (the phone attached by usb, `ANDROID_SERIAL` names one
  of several). The sdk is installed by `grocery:android:sdk`, without the emulator by `grocery:android:sdk:build`.
- The release apk: `grocery:android:release:build` writes `build/sak-grocery-<VERSION>.apk` and its `.sha256`; the
  versionCode comes from the version (1.4.2 is 10402, `latest` is 1). It is signed with the keystore of
  `ANDROID_KEYSTORE` (base64) and its passwords, the secrets of the workflow (`github:secrets:update`, from
  `pass` `sak/android/signing-key` and `sak/android/signing-key-password`); a release is never unsigned. The job
  `grocery-android` builds it on every build, signed for a tag only; the job `release` adds it to the github
  release of the tag (`grocery:android:release`). The key is the identity of the app: an update with another key
  does not install over it.
- The response classes in `api/ApiModels.kt` are copies of the ones of `sak-backend`, with the fields the app uses
  only; change them with the api.
- The logic is in `GroceryModel`, without android, tested on the jvm against a fake backend (`FakeBackend`).
- Every text the user reads is in `i18n/texts.yml` at the root of the repository: `android` and `common` for texts of
  no app, `grocery.android` and `grocery.common` for the ones of the grocery lists, e.g. `grocery.form.save` is
  `R.string.grocery_form_save`. `mise run i18n:generate` writes them into `res/values/strings.xml` (english) and
  `res/values-de/strings.xml` (german), which are not edited; sak-i18n refuses a text without its german one. The model has no android: it holds a `UiText`, a string resource with its
  arguments or a text the backend sent, and the ui reads it. The api sends the languages of the phone
  (`Accept-Language`), so the backend answers its texts in the same language. The tests read the english texts
  (`english()`).
