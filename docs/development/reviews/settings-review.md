---
title: "Review: settings"
linkTitle: "Settings"
description: "The review of sak-settings: the settings of the instance and of each user, their definitions, SettingService, the api of the admins and of the users, and its tests."
weight: 9
---

# Review: settings

**Created:** 2026-11-26

Scope: the module `sak-settings` as of `8b5ce09d`: the definitions (`io.pelle.sak.settings.model`), `SettingService`,
the routes of the admins (`SakSettings.kt`) and of the users (`user/SakUserSettings.kt`), and its tests. The groups the
backend defines (OpenRouter, the S3 storage, restic, the AI of the notes and the grocery lists) only where they show how
the module is used. The question: is the structure sound, is it tested where it matters, and what breaks or misleads.

How it was done: the code and the tests were read; nothing ran beyond the tests of the module and of the backend. The
fixed findings are in the history of git.

## What is good

- **One way to a setting.** Every read and write goes through `SettingService` with the definition; the definition
  knows its scope, type, default, level and what it requires, so a caller can not ask with the wrong scope or type.
- **Who reaches what.** A user reaches only their own values: the routes take the user from the session, the repository
  filters by `user` in its sql (`MODEL-6`), and a request naming a setting of the other level is an unknown setting.
- **A change is all or nothing.** `validate` collects every problem before anything is written, `set` writes one scope
  in one transaction, a replaced value stays as a deleted row.
- **A setting that does not count is safe.** It is `null` to the code, answers 409 with a reason, and may always go
  back to its default, so a consent can be withdrawn also while the AI is not set up.
- **Tests of the rules.** `SettingServiceTest` and `UserSettingsTest` cover the defaults, masks, read-only values, the
  switch of a group, chained requirements, the check of a group, both levels and the views by level.

## Could fix

### `SET-7` `SettingService` does three jobs

It reads and writes values, keeps the registry of the groups, and handles the requests of the api (`validate`,
`update`, the views by level, `json`): 275 lines. The request side as a class of its own next to the routes would
leave `SettingService` with the values and the groups.

### `SET-8` The check of a group never sees the values of the users

`checkOf` runs only for the instance (`user == null` in `set` and `validate`). A group with settings of the users and a
check registers without a word, and its check never runs for them. Either `register` refuses that, or the check gets the
user too.

### `SET-9` The switch of a group does not turn off the settings of its users

`get` honours `SettingsGroup.enabledBy` only for settings of the instance. A group with a switch and settings of the
users keeps the users' settings on while the admins turned the group off. No group has both today; which way it should
be is not written down.

## Notes

- `SET-11` (accepted for now): a change is validated, the check asks the provider (OpenRouter, Hetzner), and then it is
  written; two admins changing the same group at once can both pass the check, the later write wins. With one admin page
  open at a time this is not seen.
- `SET-12` (accepted): a masked text no longer than its mask shows in full (`TextSetting.toApiJson`), e.g. an api key of
  four characters with `mask = 4`. The keys of OpenRouter and Hetzner are far longer.
- `SET-13`: the registry (`registered`, a `LinkedHashMap`) is not synchronized. Every group is registered while the
  backend starts, before the first request, so this is safe as long as no group is registered later.
