---
title: "Todo"
description: "An app to manage todo lists."
weight: 45
---

# Todo

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

An app to manage todo lists: tasks with a priority and a due date, done ones struck through until they are removed.
It learns nothing.

## General

- `TODO-1` **Lists.** Todo is a list based app (refer to [List-based apps](INDEX.md#list-based-apps)).
- `TODO-2` **Add a task.** A list has an input field at its top, with a due date and a priority next to it (`TODO-4`,
  `TODO-5`). The user types the title of a task there and adds it to the list; the field is empty again, the day and the
  priority go back to none and normal. Nothing is suggested.
- `TODO-3` **Done.** A tap marks a task done, another tap opens it again. A done task stays in its place, struck through
  with a red line, until its list is purged (`TODO-6`); marking a task done or open never moves it.
- `TODO-4` **Due date.** A task has a day it is to be done by, or none. The row shows it, in red once the day has passed
  (the day of the device, again after midnight). The menu of a row (`UX-12`) adds a due date to a task without one and
  removes it from one with one.
- `TODO-5` **Priority.** A task is of high, normal or low priority, normal unless chosen. A list shows its tasks by
  priority, the high ones first, then in the order they were added; a high one shows an arrow up, a low one an arrow down
  in a column of its own, so the titles of all rows align. The menu of a row changes the priority, of a done task too.
- `TODO-6` **Purge.** A trash can in the toolbar of a list removes its done tasks according to `UX-4.2`: they leave the
  list at once, undo brings them back. Only the done tasks the user saw go; a task done meanwhile, by them or another
  user, stays. A purged task is deleted (`MODEL-3`).

## Data

The lists and their tasks (`todo_lists`, `todo_items`). A task has a title, a due date (`DATE`, none for no day), a
priority (`high`, `normal`, `low`) and when it was done (`done_at`, none while it is open); the database takes a due
date only as a real day, `yyyy-mm-dd`.

## API

Read, create, rename, share and delete a list; read the tasks of a list; add a task; mark it done or open, change its
priority and its due date; purge the done tasks a request names (`POST /api/v1/todo/lists/{id}/purge`, `TODO-6`);
listen to the changes of the lists (`GET /api/v1/todo/changes`, `LIST-4`).

## Settings

None.

## External systems

None.
