---
title: "UI and UX: web app"
linkTitle: "UI/UX web app"
description: "How the web app looks and behaves, in addition to the rules of all apps."
weight: 21
---

# UI and UX: web app

How the web app looks and behaves. It adds to the rules of all apps in [UI-UX.md](UI-UX.md); the rules are written as
[RULES.md](../RULES.md) says.

## Rules

- `WEB-1` **List, then detail.** Each kind of thing has a list that leads to a detail view. A list shows one page at a
  time and remembers its page in the address. Creating starts from the list; the actions on a thing are at the top right
  of its detail view, its delete also in the menu of its row (`UX-12`).
- `WEB-2` **An overview on top, tabs below.** An overview on top (name, created, updated, the main facts), a tab folder
  below: the active tab and its content are one card, the other tabs lie flat on the page; the first tab is
  "Configuration".
- `WEB-3` **No modal dialogs.** Create and edit are pages of their own, with all editable fields at once, saved or
  cancelled. A delete asks inline, in the view itself.
- `WEB-4` **Copying looks the same everywhere.** Copy to the clipboard looks and behaves the same everywhere.
