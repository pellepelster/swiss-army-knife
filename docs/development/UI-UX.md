---
title: "UI and UX"
description: "How the apps, the sakctl ui and the sakctl cli look and behave."
weight: 20
---

# UI and UX

How the apps look and behave for the people who use them: the web ui, the sakctl ui and the sakctl cli. How the system
is built is in [SYSTEM.md](architecture/SYSTEM.md), how the code does it in
[AGENT.md](../../AGENT.md).

Every rule has an id: the prefix of its chapter and a number, e.g. `UX-1`; a part of a rule adds a number, e.g.
`UX-4.1`. An id never changes and is never used again, also when its rule goes; a new rule gets the next number of
its chapter. Code, documents and reviews refer to a rule by its id.

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
    for 2 seconds, unless the rule of the app gives another time (e.g. `GROCERY-3`). The backend learns it only when
    the time is up.

- `UX-5` **Dates and times** are shown in the locale of the user, never in a fixed format.
- `UX-6` **Fields are checked when they lose focus.** A field is checked when the user leaves it, not while typing. The
  error is shown right under the field, in the danger color. While a field is invalid, the action that depends on it
  (next, create, save) is disabled; typing in the field again clears the error until the field is left again. The
  same check runs once more when the action is taken, and in the sakctl cli, where an invalid value ends the
  command with an error.
- `UX-7` **The sign-in follows the user mode.** The user mode comes from the deployment (`GET /api/v1/info`).
  - The web app: in the multi user mode, the login by e-mail, with a link below it to the login with the password of
    the admin. In the single user mode, only the login with the password.
  - The Android apps: three ways, the QR code (`SECURITY-3`), the password of the admin and a link by e-mail. In the
    single user mode the link by e-mail is disabled, with the reason (`UX-8`). Each way opens its form; back leads to
    the three ways again.
- `UX-8` **A button is disabled, never hidden.** A button that does not apply yet, or not to what the page or screen
  shows, stays in its place, disabled; the layout does not change under the user's hand.
- `UX-9` **The steps of a wizard and the buttons of a form, bottom right.** The buttons that move through the steps of
  a wizard (back, next, cancel, plan, create), and those that end a form (cancel, save, create, add), are one grouped
  toolbar at the bottom right; back is always its leftmost button, the main action its rightmost.
- `UX-10` **The actions of a page, top right.** The buttons that act on what the page shows (add a provider, create a
  deployment, edit a list) are one grouped toolbar at the top right of its header. One action of a page is the primary
  one, and the rightmost. A delete is the leftmost; while it asks for confirmation, Delete and Keep take the place of
  the toolbar.
- `UX-11` **Lists look alike.** Every list shows its rows as cards on the surface color of the theme, one under the
  other. A row opens what it shows.
- `UX-12` **A menu on each row.** Each row of a list has a menu behind a ⋮ button at its right end. Its last entry is
  Delete, red; it asks inline in the row, Keep is the safe default (`UX-4.1`). An entry that does not apply to the row
  is disabled, its tooltip says why (`UX-8`).
- `UX-13` **A menu on the start page.** The start page has a ⋮ menu at the top right of its header, with "Sign out".
  An app that can update itself (e.g. the Android apps, not the web ui, which a browser updates) has "Check for updates"
  in it too: the check loads a new version at once when there is one; otherwise a line under the header says what it
  found.
- `UX-14` **The robot starts the AI features.** Each action that starts an AI feature has the robot icon of the theme
  (`fa-robot`), in every app. Examples are the action that discovers the text of a photo or a recording of a note and
  the action that reads the items in a photo for a grocery list.
- `UX-15` **An error is shown where it was caused.** The error of a call is shown next to the element that made the
  call, in the danger color: the error of a save in its form, above its buttons; the error of an action of a row in
  that row; the error of an action of a page next to its toolbar (`UX-10`); the error of loading a tab in the card of
  that tab. Only a page that could not be loaded shows its error at the top, in the place of what it would show. The
  error of a field is the one of `UX-6`, right under the field.

## Web ui

- `WEB-1` **List, then detail.** Each kind of thing has a list that leads to a detail view. A list shows one page at a
  time and remembers its page in the address. Creating starts from the list; the actions on a thing are at the top right
  of its detail view, its delete also in the menu of its row (`UX-12`).
- `WEB-2` **Detail view.** An overview on top (name, created, updated, the main facts), a tab folder below: the active
  tab and its content are one card, the other tabs lie flat on the page; the first tab is "Configuration".
- `WEB-3` **No modal dialogs.** Create and edit are pages of their own, with all editable fields at once, saved or
  cancelled. A delete asks inline, in the view itself.
- `WEB-4` **Copy to the clipboard** looks and behaves the same everywhere.

## Android apps

- `ANDROID-1` **Material 3, the colors of the phone.** The Android apps follow the light or dark mode of the system
  and take the colors of the wallpaper where Android has them.
- `ANDROID-2` **One screen at a time.** The deployment, the sign-in, the first screen of the app (e.g. the overview
  of the lists, the wall of the notes) and the screens it leads to. The first screen has the name of the app as its
  title; at its top right are its main create action and the menu with the check for updates and the sign-out.
  Every other screen of a signed in user has a back arrow at the top left with its own title; the back of the system
  does the same.
- `ANDROID-3` **Pull down to reload.** Every screen that shows data of the deployment loads it again when the user
  pulls it down.

## sakctl

- `CTL-1` **A plan that destroys asks first.** Create, deploy and destroy show the plan; when it destroys a resource or
  creates one anew, sakctl asks for a confirmation before it applies: the sakctl ui in a dialog, the sakctl cli by
  having the name of the deployment typed. `--yes` skips it in the sakctl cli; without a terminal and without `--yes`
  the command stops with a usage error and changes nothing.
- `CTL-2` **No surprise by default.** A deploy keeps the settings of the deployment (e.g. DNS mode, root domain) unless
  they are given; a default never changes a running deployment.
- `CTL-3` **Feature parity.** The sakctl ui and the sakctl cli can do the same: what one does, the other
  does too.

## sakctl ui

- `UI-8` **Plan before change.** Create, deploy and destroy show a plan first: what will be created, changed,
  created anew or deleted. The plan of a deploy is the check on the page of the deployment. Only the apply changes
  something, and it shows each resource as it goes, with the output of the provisioning below.
- `UI-9` **A deploy keeps what holds data.** A deploy may create the server anew. The data volume and the addresses
  always stay; a change that would need them created anew stops the plan before anything changes. A deploy happens where
  the deployment is, never at a default location.
- `UI-10` **A destroy says what goes.** It asks only for the password. It keeps the data volume, unless the user
  ticks a box to delete it too; then the form turns red. The plan and a last confirmation dialog say what goes.
- `UI-11` **One line per event:** date, level, message, nothing indented. On a terminal the level is colored (info
  green, warning yellow, error red); a warning deserves a second look, e.g. a change the plan found or a resource a
  destroy deletes.

## sakctl cli

- `CLI-1` **A secret is never an argument**, it would show in the list of processes. Every secret comes from its
  environment variable or is typed on the terminal without echo; its flag (e.g. `--password`) asks even when the
  variable is set. The password of a new deployment is typed twice.
- `CLI-2` **Test deployments.** `--acme-ca staging` takes the certificates from the staging ca of Let's Encrypt, without
  the limits of production; the deployment keeps it and sakctl trusts its roots. The integration tests use it.
- `CLI-4` **ssh, in the sakctl cli only.** `sakctl ssh <name> [-- <command>]` opens a root session or runs a command,
  from the image of the tools: the key derived from the password lives only in an ssh-agent of the container, the
  server is checked against its derived host key.
- `CLI-6` **Scriptable.** Every input is an argument or an environment variable, `--help` lists them all.
- `CLI-8` **Exit codes tell what happened.** Success, failure, wrong arguments and each refusal a script may handle
  have a code of their own, listed by `--help`.
