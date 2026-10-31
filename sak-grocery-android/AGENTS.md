# Grocery Android app

How this app is built is in [GROCERY.md](../docs/development/architecture/apps/GROCERY.md), "Android app"
(`GROCERY-10` to `GROCERY-13`), which adds to [apps/INDEX.md](../docs/development/architecture/apps/INDEX.md) and to
the system architecture; its look is in [UI-UX.md](../docs/development/UI-UX.md), "Android app".
Every change here keeps to them; a change that breaks a rule of the app changes GROCERY.md in the same commit.

- A gradle build of its own with its own wrapper: `mise run grocery:android:build`, `grocery:android:test`,
  `grocery:android:run` (emulator), `grocery:android:install` (the phone attached by usb, `ANDROID_SERIAL` names one
  of several). The sdk is installed by `grocery:android:sdk`.
- The response classes in `api/ApiModels.kt` are copies of the ones of `sak-backend`, with the fields the app uses
  only; change them with the api.
- The logic is in `GroceryModel`, without android, tested on the jvm against a fake backend (`FakeBackend`).
