# Notes Android app

How this app is built is in [NOTES.md](../../docs/development/architecture/apps/NOTES.md); what all android apps
share is in [../AGENTS.md](../AGENTS.md). Every change here keeps to them; a change that breaks a rule of the app
changes NOTES.md in the same commit.

- `mise run notes:android:run` (emulator) and `notes:android:install` (the phone attached by usb); its release apk is
  `sak-notes-<VERSION>.apk`.
- The logic is in `NotesModel` (on top of `SakModel` of core), tested in `NotesModelTest` against the fake backend of
  `testing`. The microphone is behind `Recorder` (`MicrophoneRecorder` records 16 kHz mono 16 bit and writes a wav,
  the only recording the backend takes); photos are scaled by `PhotoScaling` of core.
- The AI discovers the text of a photo or a recording only when the settings of the notes turn it on
  (`GET /settings/notes?effective=true`, off when they can not be read), as the web app; the backend refuses it
  otherwise. The app never turns it on: an admin does, with the consent that names OpenRouter.
- No stream of changes: the notes load again when the app comes to the front, the text typed is saved when it goes.
- Its texts are `notes.android` (and `notes.common`) in `i18n/texts.yml`, e.g. `R.string.notes_create_note`.
