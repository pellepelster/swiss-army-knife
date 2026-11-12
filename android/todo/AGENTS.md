# Todo Android app

How this app is built is in [TODO.md](../../docs/development/architecture/apps/TODO.md); what all android apps
share is in [../AGENTS.md](../AGENTS.md). Every change here keeps to them; a change that breaks a rule of the app
changes TODO.md in the same commit.

- `mise run android:run todo` (emulator) and `android:install todo` (the phone attached by usb); its release apk is
  `sak-todo-<VERSION>.apk`.
- The logic is in `TodoModel` (on top of `SakModel` of core), tested in `TodoModelTest` against the fake backend of
  `testing`.
- Its texts are `todo.android` and `todo.common` in `i18n/texts.yml`, e.g. `R.string.todo_form_create`.
