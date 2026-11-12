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

### ~~`TD-4` Nothing stops a test that JUnit skips without a word~~

**Fixed:** the tests of every module of `sak.java-conventions` run with
`junit.platform.discovery.issue.severity.critical=WARNING`: JUnit reports a `@Test` method that returns a value
("must not return a value") as a discovery issue of that severity, which now fails the run. None of the 451 tests
did; a test that returns a value fails the build from now on. The Android apps run JUnit 4, which fails such a test
already.

A test written as `fun x() = runBlocking { … }` whose last expression is a value (e.g. `shouldBe`) returns that value,
and JUnit 5 skips a test method that is not void without failing. 141 functions of the test classes are written as
an expression without a return type, 5 with `: Unit =`; nothing in the build checks them.

Fix: a check in the build, e.g. a test of `sak-backend` that finds every method annotated with `@Test` that does not
return void, or a rule of detekt; until then, `: Unit =` on every test that is an expression.

## Could fix

### ~~`TD-5` The initial bundle of the web app is over its budget~~

**Fixed:** 1 to 3 below. Every page but the home, the login and the sign-in is loaded with its first visit (the
pages of an app in `<app>.pages.ts`), the login has signals instead of `@angular/forms`, and `src/icons` has the css
of the 43 icons the app uses and the solid font cut to them (`mise run frontend:icons`, a spec fails while they are
not current). The initial bundle is 631 kB (127 kB to transfer, was 197 kB), the font 4 kB instead of 117 kB; the
budget warns from 700 kB. 4 and 5 are still possible.

`ng build` warns: the initial bundle is 996.18 kB (977.76 kB when the todo app was added), the budget warns from 900
kB (`angular.json`, the error is at 1.5 MB). Only the access logs of a bucket and the page that connects an app are
loaded lazily.

What it is (`ng build --stats-json`, 2026-11-12): 677 kB of script and 319 kB of css.

| Part | Initial | What |
|---|---|---|
| the pages of the apps | 260 kB | grocery 61, hosting 42, notes 40, redirects 29, todo 22, settings 12, users 11, health 6, downloads 3, all loaded at the start |
| `@angular/cdk` | 81 kB | only for the drag and drop of the categories (`grocery-list-form.ts`) |
| `@angular/forms` | 45 kB | only for the form of the login (`login.ts`), every other form uses signals |
| the texts | 53 kB | `i18n/catalog.ts` with English and German, one of them is used |
| Bootstrap | 225 kB | the whole `bootstrap.min.css`; the pages use alerts, forms, cards, dropdowns, buttons, list groups, badges and a few more |
| Font Awesome | 70 kB | the css of every icon (and a font of 117 kB, not counted); the pages use 45 icons |
| Angular, rxjs, the rest | 260 kB | core 178, router 97, common 35, rxjs 28, platform-browser 16, service worker 7 |

What helps, the most first:

1. **Every app a route of its own, loaded lazily** (`loadChildren`/`loadComponent` for notes, grocery, todo, hosting,
   redirects, users, settings, health, downloads): about 340 kB less at the start, the CDK goes with grocery. The home,
   the login and the sign-in stay. Alone it takes the initial bundle to about 650 kB.
2. **The login without `@angular/forms`**, with signals like the other forms: 45 kB.
3. **Only the icons that are used**: a css of the 45 icons (a script of the build, from the names in `src/app`) instead
   of `fontawesome.min.css`, about 65 kB less, and the font cut to them (`pyftsubset`), about 110 kB less to download.
   `fa-face-smile` is asked for as `fa-regular`, whose font is not loaded.
4. **Only the parts of Bootstrap that are used**, built from its scss in `sak-theme`: perhaps 100 to 150 kB. sakctl
   uses the same css (`sak-cli/ui/theme`), so `sak-theme` would build it for both.
5. **The texts of one language**: a catalog for each language, the one of the user loaded at the start: about 26 kB.

Fix: 1 first, it is the largest and the simplest; then 2 and 3. The budget can then go back below 900 kB, so a page
that is no longer lazy shows at once.

### `TD-9` Not checked with this review

- the date picker of the todo app in landscape was tested for a reachable OK button, nobody looked at a picture of it
- the e2e tests in Hetzner Cloud (`mise run cli:test:e2e`) did not run; the todo app does not change sakctl
- `prettier --check src e2e` still flags the generated `src/app/i18n/catalog.ts` and `e2e/login.spec.ts`,
  `e2e/logs.spec.ts` and `e2e/swagger.spec.ts`; no task of mise and no script of `package.json` runs prettier
