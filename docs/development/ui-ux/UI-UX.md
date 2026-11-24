---
title: "UI and UX"
description: "How all apps look and behave: the rules all apps share."
weight: 20
---

# UI and UX

How the apps look and behave for the people who use them, the rules all apps share. What only one kind of app does is in
[UI-UX-WEB.md](UI-UX-WEB.md), [UI-UX-ANDROID.md](UI-UX-ANDROID.md) and [UI-UX-DESKTOP.md](UI-UX-DESKTOP.md). What only
sakadmin does, in its ui and its cli, is in [SAKADMIN.md](../architecture/components/SAKADMIN.md). How the system is built
is in [SYSTEM.md](../architecture/SYSTEM.md), how the code of the web app does it in `WEB-6` to `WEB-10` of [the
architecture of the components](../architecture/components/INDEX.md#web-app).

The rules are written as [RULES.md](../RULES.md) says.

## All apps

- `UX-1` **One look.** All apps use the same theme, its colors, fonts and status lights. They follow the light or dark
  mode of the system and have no switch of their own.
- `UX-2` **Only the main create action has a plus.** A page has at most one button with the plus sign of the theme: the
  one that creates what the page is about (a note, a static hosting, a redirect, a list). Other add actions show only
  what they add, as its icon (a photo, a recording, an item of a grocery list); their label and tooltip say what they
  do. A small inline link to add something (`+ label`) may start with a plus in its text.
  - `UX-2.1` **Create or Add.** "Create" makes something new that has a page or screen of its own (a list, a note, a
    static hosting, a redirect, a user, a deployment); "Add" puts something into what the page shows (an item or a
    category into a list, a domain into a static hosting, a photo or a label into a note, a token into the settings).
  - `UX-2.2` **The verb with its noun, the same everywhere.** The label is the verb with what it makes ("Create list",
    "Liste erstellen"; "Add category"), and the same text is its tooltip, the title of the page or screen it leads to
    and the label of the button that ends it.
  - `UX-2.3` **No "New".** No action says "New …"; "new" only names a thing being made (e.g. "the new note").
- `UX-3` **Destructive is red.** A button that destroys or deletes something has the danger color. The button
  that does it is red; a toolbar button that only leads to it is outlined red. No other button is red.
- `UX-4` **Two ways to delete data.** An action that deletes something that holds data or configuration the
  user made does one of two things:
  - `UX-4.1` **Confirm first.** It asks for confirmation and says what goes. Cancel is the safe default.
  - `UX-4.2` **Undo afterwards.** It takes effect in the ui at once; a toast centered at the bottom offers to undo it
    for a short time. The backend learns it only when the time is up.
- `UX-5` **Dates and times follow the locale.** They are shown in the locale of the user, never in a fixed format.
- `UX-6` **Fields are checked when they lose focus.** A field is checked when the user leaves it, not while typing. The
  error is shown right under the field, in the danger color. While a field is invalid, the action that depends on it
  (next, create, save) is disabled; typing in the field again clears the error until the field is left again. The
  same check runs once more when the action is taken, and in the sakadmin cli, where an invalid value ends the
  command with an error.
- `UX-7` **The sign-in follows the user mode.** The user mode comes from the deployment (`GET /api/v1/info`).
  - The web app: in the multi user mode, the login by e-mail, with a link below it to the login with the password of
    the admin. In the single user mode, only the login with the password.
  - The Android apps: three ways, the QR code (`SECURITY-3`), the password of the admin and a link by e-mail. In the
    single user mode the link by e-mail is disabled, with the reason (`UX-8`). Each way opens its form; back leads to
    the three ways again.
- `UX-8` **A button is disabled, never hidden.** A button that does not apply yet, or not to what the page or screen
  shows, stays in its place, disabled; the layout does not change under the user's hand.
- `UX-9` **Wizard and form buttons at the bottom right.** The buttons that move through the steps of
  a wizard (back, next, cancel, plan, create), and those that end a form (cancel, save, create, add), are one grouped
  toolbar at the bottom right; back is always its leftmost button, the main action its rightmost.
- `UX-10` **The actions of a page, top right.** The buttons that act on what the page shows (add a provider, create a
  deployment, edit a list) are one grouped toolbar at the top right of its header. One action of a page is the primary
  one, and the rightmost. A delete is the leftmost; while it asks for confirmation, Delete and Keep take the place of
  the toolbar.
- `UX-11` **Lists look alike.** Every list shows its rows as cards on the surface color of the theme, one under the
  other. A row opens what it shows.
- `UX-12` **A menu on each row.** Each row of a list has a menu behind a ⋮ button at its right end. Its last entry is
  Delete, red; it asks inline in the row (on Android in a dialog, `ANDROID-4`), Keep is the safe default (`UX-4.1`). An
  entry that does not apply to the row is disabled, its tooltip says why (`UX-8`).
- `UX-13` **A menu on the start page.** The start page has a ⋮ menu at the top right of its header, with "Sign out". An
  app that can update itself (e.g. the Android apps, not the web app, which a browser updates) has "Check for updates"
  in it too: the check loads a new version at once when there is one; otherwise a line under the header says what it
  found. The app also checks on its own when it starts, at most once in 7 days; a newer version is then offered in a
  banner at the top of the start page, which "Later" closes until the next check. The desktop apps have neither, their
  update and their account are placed by `DESKTOP-4`.
- `UX-14` **The robot starts the AI features.** Each action that starts an AI feature has the robot icon of the theme
  (`fa-robot`), in every app. Examples are the action that discovers the text of a photo or a recording of a note and
  the action that reads the items in a photo for a grocery list.
- `UX-15` **An error is shown where it was caused.** The error of a call is shown next to the element that made the
  call, in the danger color: the error of a save in its form, above its buttons; the error of an action of a row in
  that row; the error of an action of a page next to its toolbar (`UX-10`); the error of loading a tab in the card of
  that tab. Only a page that could not be loaded shows its error at the top, in the place of what it would show. The
  error of a field is the one of `UX-6`, right under the field.
