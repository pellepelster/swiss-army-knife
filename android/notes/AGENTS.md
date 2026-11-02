# Notes Android app

How this app is built is in [NOTES.md](../../docs/development/architecture/apps/NOTES.md); what all android apps
share is in [../AGENTS.md](../AGENTS.md). Every change here keeps to them; a change that breaks a rule of the app
changes NOTES.md in the same commit.

- `mise run android:run notes` (emulator) and `android:install notes` (the phone attached by usb); its release apk is
  `sak-notes-<VERSION>.apk`.
- The logic is in `NotesModel` (on top of `SakModel` of core), tested in `NotesModelTest` against the fake backend of
  `testing`. The microphone is behind `Recorder` (`MicrophoneRecorder` records 16 kHz mono 16 bit and writes a wav,
  the only recording the backend takes); photos are scaled by `PhotoScaling` of core.
- The AI discovers the text of a photo or a recording only when it is turned on for the deployment
  (`GET /notes/settings`, which every signed in user may read; off when it can not be read), as the web app; the
  backend refuses it otherwise. The app never turns it on: an admin does, with the consent that names OpenRouter.
- A note is saved one save at a time (a change during a save sends it once more after the answer); a load keeps what
  changed here since it started; a change the backend refuses goes back to the note as it was stored. The limits of
  the backend (photos, recordings, labels, text) are checked in the app. A photo or a recording taken off comes back
  with undo until `UNDO_REMOVE_MS`. Saves and the upload of a recording run with `launchLasting`, so they end also
  when the app closes.
- No stream of changes: the notes load again when the app comes to the front, the text typed is saved when it goes.
- Its texts are `notes.android` (and `notes.common`) in `i18n/texts.yml`, e.g. `R.string.notes_create_note`.
