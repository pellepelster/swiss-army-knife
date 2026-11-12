---
title: "Review: todo app"
linkTitle: "Todo app"
description: "The review of the todo app (backend, data model, web and Android) and of what it shares with the grocery lists: what is still open after the fixes made with it."
weight: 7
---

# Review: todo app

**Created:** 2026-11-12

**Checked again:** 2026-11-12, every finding listed is still open; the fixed ones are in the history of git.

Scope: the todo app as it was added (changeset `0002-todo`, `TodoRepository`, `TodoService`, the routes under
`/api/v1/todo`, `sak-frontend/src/app/todo/`, `android/todo/`) and the code it has in common with the grocery lists
and the notes. The question: bugs, code that can be shared with the other list apps, simplifications, and the data
model.

What is good: every query checks who sees and changes a list in its sql (MODEL-6); soft deletes are filtered
everywhere, a deleted list takes its tasks with it; every change reaches the change stream (LIST-4); the values are
validated with texts of the catalog; a due date is stored as text (`yyyy-mm-dd`), independent of the time zone of the
server; the date picker of the Android app converts in UTC both ways.

Only the open findings are listed.

## Should fix

### `TD-2` A single task can not be deleted or renamed

There is no delete and no rename of a task, in the api or the clients: a task added by mistake goes only by marking it
done and purging the list. LIST-2 says every user who sees a list deletes an item of it.

Fix: decide whether the todo app follows LIST-2 here; if so, `DELETE /api/v1/todo/items/{id}` and a rename, from the
menu of a row, as the grocery lists have them.

### `TD-4` Nothing stops a test that JUnit skips without a word

A test written as `fun x() = runBlocking { … }` whose last expression is a value (e.g. `shouldBe`) returns that value,
and JUnit 5 skips a test method that is not void without failing. 141 functions of the test classes are written as
an expression without a return type, 5 with `: Unit =`; nothing in the build checks them.

Fix: a check in the build, e.g. a test of `sak-backend` that finds every method annotated with `@Test` that does not
return void, or a rule of detekt; until then, `: Unit =` on every test that is an expression.

## Could fix

### `TD-5` The initial bundle of the web app is over its budget

`ng build` warns: the initial bundle is 996.18 kB (977.76 kB when the todo app was added), the budget warns from 900
kB (`angular.json`, the error is at 1.5 MB). Only the access logs of a bucket and the page that connects an app are
loaded lazily.

Fix: load the list apps lazily (their routes with `loadChildren`), or raise the budget knowingly.

### `TD-9` Not checked with this review

- the date picker of the todo app in landscape was tested for a reachable OK button, nobody looked at a picture of it
- the e2e tests in Hetzner Cloud (`mise run cli:test:e2e`) did not run; the todo app does not change sakctl
- `prettier --check src e2e` still flags the generated `src/app/i18n/catalog.ts` and `e2e/login.spec.ts`,
  `e2e/logs.spec.ts` and `e2e/swagger.spec.ts`; no task of mise and no script of `package.json` runs prettier
