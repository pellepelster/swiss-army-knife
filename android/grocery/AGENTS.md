# Grocery Android app

How this app is built is in [GROCERY.md](../../docs/development/architecture/apps/GROCERY.md); what all android apps
share is in [../AGENTS.md](../AGENTS.md). Every change here keeps to them; a change that breaks a rule of the app
changes GROCERY.md in the same commit.

- `mise run grocery:android:run` (emulator) and `grocery:android:install` (the phone attached by usb); its release
  apk is `sak-grocery-<VERSION>.apk`.
- The logic is in `GroceryModel` (on top of `SakModel` of core), tested in `GroceryModelTest` against the fake
  backend of `testing`.
- Its texts are `grocery.android` and `grocery.common` in `i18n/texts.yml`, e.g. `R.string.grocery_form_save`.
