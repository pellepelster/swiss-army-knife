---
title: "Review: the texts of i18n/texts.yml"
linkTitle: "Texts"
description: "The review of the texts users read (i18n/texts.yml), in English and German: consistency, the glossary, odd wording."
weight: 13
---

# Review: the texts of i18n/texts.yml

**Created:** 2026-11-23

Scope: the 1502 texts of [i18n/texts.yml](../../../i18n/texts.yml), in English and German, against the header of the
file and the [glossary](../GLOSSARY.md). The question: texts that say something wrong, texts that say one thing in
two ways, and wording a careful editor would change. The plain style of the file (short sentences, "can not") is on
purpose and is no finding.

Checked by script over all texts: the placeholders, the punctuation at the end, the forms of address, the words of
the glossary, the quotes. German uses "du" in every text, as the header of `texts.yml` says. Read by hand: every text.

What is good: every text has both languages with the same placeholders and the same punctuation at the end; no text
is left untranslated; the verb is "sign in" ("anmelden") everywhere; the resource status words of the glossary are
used as defined; the labels are in sentence case.

A key is written without its file, e.g. `todo.common.list.not_done`.

## Should fix

### `TX-4` German "SAK-Instanz" as a masculine word

"die SAK-Instanz" is feminine; four texts refer to it with "sein":

- `admin_cli.ui.deployment.changes`: "%{count} seiner Ressourcen" → "ihrer Ressourcen"
- `admin_cli.ui.confirm.text`, `admin_cli.ui.confirm.text_with_data`: "Seine DNS-Einträge, sein Server, …, sein
  Datenvolume" → "Ihre DNS-Einträge, ihr Server, …, ihr Datenvolume"
- `admin_cli.common.deployments.settings_unread`: "aus seinem Backend" → "aus ihrem Backend"

### `TX-5` One resource "have changes"

`admin_cli.ui.deployment.changes` ("%{count} of its resources have changes.") is wrong for a count of 1, and the
desktop texts have no plural forms (only android has them, header of `texts.yml`). Fix: "Resources with changes:
%{count}" ("Ressourcen mit Änderungen: %{count}").

## Glossary

### `TX-8` "remove" for a deletion

The glossary allows "remove" only for a label, a photo or a recording removed from a note. Other uses:

- deletions: `todo.common.list.purge`, `.purged`, `.not_purged` (a purge deletes the done tasks, `TODO-6`); the
  snapshots a backup plan no longer keeps (`sak_backup.ui.plans.keep_hint`, `run_expiring`, `run_removed`,
  `run_expire_failed`, `run_empty`, `sak_backup.cli.expiring`, `removed`, `expire_failed`). Fix: "delete" ("löschen").
- not clear: `grocery.common.photo.remove` and `.not_removed` (the photo of an item, as the photo of a note?),
  `sak_backup.ui.plans.remove_path` (a path of a backup plan), `todo.common.list.remove_due_date` (a value, no thing a
  user made). Fix: decide, and write the decision in the glossary.

### `TX-9` "backup" for the data of restic

The glossary: a backup is the file sakadmin makes of a SAK instance; what restic keeps is a repository with
snapshots. These texts say "backup" for the data of restic:

- `backend.restic.settings.label` ("Restic backups"), `.description`, `backend.restic.settings.s3.label`,
  `.s3.description` → "repositories" (as `web.app.backups_description` says).
- `sak_backup.ui.intro` ("to manage its backups") → "your backup plans and repositories".
- `sak_backup.ui.repositories.create_hint` ("Its backups are encrypted"), `sak_backup.ui.plans.run_password_hint`,
  `sak_backup.cli.repository_missing` ("so it had backups"), `sak_backup.cli.needs_init` ("has no backup yet") →
  "snapshots".

sakbackup also says "the backup" for one run of a backup plan (`sak_backup.ui.plans.run_running`, `sak_backup.ui.plans.run_failed`, `sak_backup.ui.tray.running`, …). The
glossary has no word for a run. Fix: decide whether "backup" is the word for a run in sakbackup, and add it to the
glossary.

### `TX-10` The names of the apps

The glossary: "SAK Notes", "SAK Grocery List" and "SAK Todo" are the installed apps, the same in every language;
inside SAK the short names "Notes"/"Notizen", "Grocery"/"Einkaufslisten", "Todo"/"Aufgaben".

- `web.connect.instructions`: "Open SAK Notes, Grocery or Todo" / "Öffne SAK Notizen, Einkaufslisten oder Aufgaben"
  → "SAK Notes, SAK Grocery List or SAK Todo" in both languages.
- `web.signin.grocery_text`, `notes_text`, `todo_text`: "the SAK grocery app" / "der SAK-Einkaufslisten-App" → the
  names of the apps.
- `todo.backend.first_list`: "Meine Todos" → "Meine Aufgaben" (as "Meine Einkäufe", "Meine Notizen").
- `backend.users.app_link_body` and `web.users.intro`: "Todo lists" / "Aufgabenlisten", "Grocery lists" → the names of
  the apps, as the links lead to them.

### `TX-11` The German noun "Deploy"

The glossary: the German verb is "ausrollen". Three texts use the noun "Deploy":
`admin_cli.common.deployments.cancelled` ("ein Deploy erledigt den Rest"), `admin_cli.common.provision.timed_out`,
`admin_cli.ui.close.text` ("der nächste Deploy"). The other texts say "Ausrollen". Fix: "ein erneutes Ausrollen", "das
nächste Ausrollen".

### `TX-12` "Swiss Army Knife" as the program

`admin_cli.common.checks.container_runtime.needed` and `admin_cli.common.checks.provider_tokens.invalid` say "start Swiss Army Knife again";
other texts of sakadmin say "SAK" or "sakadmin" for the program that acts. The program a person starts again is
sakadmin. Fix: "sakadmin".

## Consistency

### `TX-13` "does not allow to see"

The 13 texts "Your role does not allow to …" (`web.settings.forbidden_see`, `forbidden_change`,
`grocery.web.lists_forbidden`, `todo.web.lists_forbidden`, `health.web.forbidden_see`, `hosting.web.*forbidden*`,
`redirects.web.*forbidden*`) are no correct English, and `hosting.web.copy_failed` ("did not allow to copy") has the
same problem. Fix: "does not allow you to see …". German: two texts say "erlaubt es nicht", eleven "erlaubt nicht";
one form. `health.web.forbidden_see` "see the health" → "see the health checks".

`web.users.forbidden_see`, `forbidden_add` ("Only an admin sees the users.") and `android.only_owner_share_delete`
("Only the owner renames, shares or unshares a list, or deletes it.") read as a description, not as an error: "Only
an admin can …", "Only the owner can rename, share, unshare or delete a list."

### `TX-16` Recognizing the text of a photo or a recording

The English has three verbs for one action: "discover" (`backend.files.not_discoverable`, `discovery_failed`,
`notes.common.card.discover`, `notes.common.errors.not_discovered`, `grocery.backend.discovery_failed`,
`backend.openrouter.settings.enabled.description`), "read" (`backend.files.too_many_discoveries`) and "transcribe" (the
descriptions of the models). The German says "erkennen" (once "verschriftlicht"). "Discover" is the name of the
feature in the code. Fix: "recognize" in all English texts. German: "auf dem Foto", not "im Foto"
(`grocery.common.discover.title`, `grocery.common.discover.none`, `notes.android.error.no_text_in_photo`, …;
`notes.web.errors.no_text_in_photo` has it right).

### `TX-17` German progress texts

The apps say "Wird gespeichert…", "Wird geladen…"; sakadmin and sakbackup say "Prüfe die SAK-Instanz…", "Speichere…",
"Lade die Repositorys…"; some say neither ("Verbinde…" in `web.connect_desktop.connecting`, `android.connecting`,
"Anmeldung läuft…"). In a text with "du", "Speichere…" reads as an instruction. `admin_cli.ui.password.checking` ("Das
Passwort wird geprüft…") is the only passive one of sakadmin. Fix: one form, "Wird …", in all programs.

### `TX-18` The link of the e-mail login

The link has three names: "login link" (`common.login.send_link`, `link_invalid`, `android.link_sent`,
`android.error.*`), "e-mail link" (`common.login.use_email`), "e-mail login" (`android.sign_in_email_single`,
`admin_cli.common.deployments.admin_email_single`). The noun of "sign in" is written "sign in"
(`web.signin.failed_title` "Sign in failed", `web.login.not_loaded`, `web.login.failed`) and "sign-in"
(`desktop_shared`, `web.connect_desktop`). Fix: "login link" for the link, "sign-in" for the noun.

### `TX-19` Quotes, spelling and case

- German quotes: `„…“` in 4 texts, `"…"` in 9 (`backend.settings.*`, `android.link_sent`). English: `“…”` in 3 texts,
  `"…"` in 10. Fix: one form for each language (a name of a command, e.g. `ssh -F "%{config}"`, keeps its quotes).
- "cannot" in 6 texts (`android.qr.denied`, `admin_cli.common.deployments.domain_mixed`,
  `admin_cli.cli.ssh_config_bad_directory`, `admin_cli.ui.hint.root_domain`, `admin_cli.ui.hint.smtp_password`,
  `admin_cli.ui.confirm.text_with_data`), "can not" in 17.
- Names in lower case: "api" (`web.home.api_description`, `hosting.backend.invalid_domain`,
  `admin_cli.common.config.no_root_domain`, `admin_cli.common.config.not_https`, `admin_cli.ui.create.dns_mode_hint_*`,
  `admin_cli.common.checks.image.no_api`),
  "github", "https" (`desktop_shared.common.update.*`), "sha-256" (German "SHA-256"). Fix: API, GitHub, HTTPS, SHA-256.
- "multi user", "single user", "lower case letters" as adjectives → "multi-user", "single-user", "lower-case"; "lower
  case" and "lowercase" both occur.
- `notes.web.card.recording.play`, `.pause`: "Play Recording %{number}" → "Play recording".
- German `<root domain>` in `admin_cli.ui.create.dns_mode_hint_subdomain`, `dns_mode_hint_domain`; all other German
  texts say `<Root-Domain>`.

### `TX-20` Other words used two ways

- German "anlegen" (`notes.web.page.empty`, `notes.web.errors.not_created`, `sak_backup.cli.needs_init`) and
  "erstellen" (all others).
- German "zeigen" and "anzeigen" for the same buttons (`grocery.*.show_bought`, `sak_backup.ui.plans.run_show` against
  `sak_backup.ui.tray.show`, `sak_backup.ui.plans.excludes_show`, `sak_backup.ui.snapshots.show`).
- German "Aufnahme stoppen" (`notes.web.recording.stop`) and "Aufnahme beenden" (`notes.android.recording_stop`).
- sakbackup: "leave out" (`sak_backup.ui.excludes.title`) and "excludes" ("Edit excludes…", "does not know excludes");
  the program "SAK Backup" and "sakbackup" (`sak_backup.ui.plans.run_elsewhere`, `sak_backup.cli.not_signed_in`).
- sakadmin: "update" and "upgrade" for the same thing (`admin_cli.common.maintenance.*`); "settings", "configuration" and
  "Config" for the configuration of a SAK instance (`admin_cli.common.config.*`, `admin_cli.ui.deployment.config.*`, the
  button "Config…").
- The empty lists: "You have no list yet, create one first." (grocery, todo web), "No notes yet, add one." (notes
  web), "Nothing to buy." (grocery android) against "Nothing to buy yet, add something." (grocery web).
- `admin_cli.cli.plan_confirm` "Der Plan löscht oder legt neu an" against `admin_cli.ui.confirm.deploy_text` "löscht
  oder ersetzt".

## Wording

Single texts a careful editor would write again. English first, then German where it has the same problem.

| Key | Now | Proposal |
|---|---|---|
| `backend.users.is_admin` | The address is an admin, it is no user to add. / Die Adresse ist ein Admin, … | This address belongs to an admin, it can not be added as a user. / Die Adresse gehört einem Admin, sie kann nicht als Benutzer hinzugefügt werden. |
| `backend.settings.requires` (de) | "%{label}" braucht "%{required}" von "%{scope}" eingeschaltet. | Für "%{label}" muss "%{required}" in "%{scope}" eingeschaltet sein. |
| `web.users.email_hint` | The user signs in with a link sent to it. No mail goes out now. | The user signs in with a link sent to this address. No e-mail is sent now. |
| `backend.openrouter.settings.enabled.description` | Providers that collect the data are not asked. | Providers that store the data or train with it are not used. |
| `backend.restic.invalid_host` | … at most 253 letters, digits, '.', '-' and '_'. | … at most 253 characters: letters, digits, '.', '-' and '_'. |
| `sak_backup.ui.invalid_name` | … at most 63, and starts … / höchstens 63 | … at most 63 characters … / höchstens 63 Zeichen |
| `sak_backup.ui.plans.keep_hint` | … of each day, week, month or year the newest stays, as many as given. Empty everywhere keeps them all. | … For each day, week, month or year the newest snapshot stays, for as many as given. If all fields are empty, all snapshots stay. |
| `sak_backup.ui.excludes.hint` | One pattern of restic a line; … | One restic pattern per line; … |
| `sak_backup.ui.plans.run_elsewhere`, `sak_backup.cli.running_elsewhere` | Another sakbackup works on this repository, e.g. the command line. | SAK Backup uses this repository at the moment, e.g. on the command line. |
| `sak_backup.ui.plans.run_busy` | Another backup or restore runs. | Another backup or restore is running. |
| `sak_backup.ui.plans.no_snapshot` | There is no snapshot to restore yet: back it up first. | … run the backup plan first. |
| `sak_backup.ui.snapshots.delete_confirm` | It can not be restored afterwards. | This can not be undone. ("restore" is the restore of a snapshot) |
| `sak_backup.ui.snapshots.not_initialized` (de) | In %{repository} ist noch kein Backup gegangen. | In %{repository} wurde noch nichts gesichert. |
| `sak_backup.ui.plans.run_init_hint`, `run_init_environment` (de) | Ohne es kann niemand … | Ohne dieses Passwort kann niemand … |
| `sak_backup.ui.tray.idle` (de) | Kein Backup läuft | Es läuft kein Backup |
| `grocery.backend.category.frozen` (de) | Tiefkühl | Tiefkühlkost |
| `notes.android.recording_longest` | … the longest a recording is. / … so lang ist eine Aufnahme höchstens. | … the longest a recording can be. / … so lang darf eine Aufnahme höchstens sein. |
| `hosting.web.route.create`, `hosting.web.create_page.title` | Create static hosting · Static hosting | Create bucket · Static hosting |
| `redirects.web.configuration.not_served` (de) | … bis er es wird. | … bis er unter einer Domain ausgeliefert wird. |
| `desktop_shared.ui.login.restore.changed` | The SAK address names another API than at your last sign-in. | The SAK address leads to another SAK instance than at your last sign-in. |
| `admin_cli.common.deployments.unverifiable` | Nothing of the SAK instance %{name} carries what its password could be checked against, … | No resource of the SAK instance %{name} has the hash to check its password against, so sakadmin does not change or show it. … |
| `admin_cli.cli.plan_confirm` | Type the name of %{name} to continue: | Type %{name} to continue: |
| `admin_cli.ui.maintenance.reboot_hint` | A reboot is pending: Maintenance, Reboot reboots the server. | A reboot is pending: choose Maintenance, then Reboot. |
| `admin_cli.common.deployments.records_in_the_way` | The names of the SAK instance are used by records of another owner: … | DNS records of another owner use the host names of the SAK instance: … |
| `admin_cli.common.provision.no_events_address`, `markers_failed` | The endpoint for the events of the playbook got no address … / Reading the markers of the roles … | sakadmin could not open a local port to follow the setup of the server. / sakadmin could not read which steps of the setup are done … |
| `admin_cli.common.smtp.refused` | The mail server %{host} refused: %{error} | … refused the e-mail: %{error} |
| `admin_cli.common.smtp.failed` | The mail server %{host} does not fit the settings: %{error} | The mail server %{host} answered with an error: %{error} |
| `admin_cli.ui.create.smtp_hint` | Without STARTTLS the connection is implicit TLS … | Without STARTTLS the connection uses TLS from the start … (the errors say "TLS without STARTTLS") |
| `admin_cli.ui.create.smtp_from_hint` | … only send from an address of their account … | … only send from an address of your account there … |
| `admin_cli.common.sak_backend.failed` (de) | Der Server ist fehlgeschlagen: %{error} | Auf dem Server ist ein Fehler aufgetreten: %{error} |
| `admin_cli.ui.update.restart_while_running` | A SAK instance is being changed, restart when it is done. | … Restart sakadmin when that is done. |
| `android.update_app_no_release`, `web.app_download.no_release`, `android.update_deployment_no_release` | This build of the app is no release … / This SAK instance runs no release … | This version of the app is not a release, it gets no updates. / This SAK instance does not run a release … |
| `web.app.notes_description` | Post-its from text, images or audio | Notes from text, photos or recordings (Post-it is a brand) |
