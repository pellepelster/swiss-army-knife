---
title: "UI and UX"
description: "How the apps, the window of sakctl and its command line look and behave."
weight: 20
---

# UI and UX

How the apps look and behave for the people who use them: the web ui, and sakctl in its window and on the command
line. How the system is built is in [SYSTEM.md](architecture/SYSTEM.md), how the code does it in
[AGENT.md](../../AGENT.md).

Every rule has an id: the prefix of its chapter and a number, e.g. `UX-1`; a part of a rule adds a number, e.g.
`UX-4.1`. An id never changes and is never used again, also when its rule goes; a new rule gets the next number of
its chapter. Code, documents and reviews refer to a rule by its id.

## All apps

- `UX-1` **One look.** All apps use the same theme, its colors, fonts and status lights. They follow the light or dark
  mode of the system and have no switch of their own.
- `UX-2` **Only the main create action has a plus.** A page has at most one button with the plus sign of the theme: the
  one that creates what the page is about (a note, a bucket, a redirect, a domain). Other add actions show only what
  they add, as its icon (a photo, a recording, an item of a grocery list); their label and tooltip say what they do. A
  small inline link to add something (`+ label`) may start with a plus in its text.
- `UX-3` **Destructive is red.** A button that destroys or deletes something has the danger color. The button
  that does it is red; a toolbar button that only leads to it is outlined red. No other button is red.
- `UX-4` **Two ways to delete data.** An action that deletes something that holds data or configuration the
  user made does one of two things:
  - `UX-4.1` **Confirm first.** It asks for confirmation and says what goes. Cancel is the safe default.
  - `UX-4.2` **Undo afterwards.** It takes effect in the ui at once; a toast centered at the bottom offers to undo it
    for 2 seconds, unless the rule of the app gives another time (e.g. `GROCERY-3`). The backend learns it only when
    the time is up.

  Things the app makes again by itself, for example a plan or a filter, need neither.
- `UX-5` **Dates and times** are shown in the locale of the user, never in a fixed format.
- `UX-6` **Fields are checked when they lose focus.** A field is checked when the user leaves it, not while typing. The
  error is shown right under the field, in the danger color. While a field is invalid, the action that depends on it
  (next, create, save) is disabled; typing in the field again clears the error until the field is left again. The
  same check runs once more when the action is taken, and on the command line, where an invalid value ends the
  command with an error.
- `UX-7` **The sign-in follows the user mode.** In the multi user mode, the sign-in shows the login by e-mail; a link
  below it leads to the login with the password of the admin. In the single user mode, it shows only the login with
  the password. The user mode comes from the deployment (`GET /api/v1/info`).
- `UX-8` **A button is disabled, never hidden.** A button that does not apply yet, or not to what the page or screen
  shows, stays in its place, disabled; the layout does not change under the user's hand.
- `UX-9` **The steps of a wizard, bottom right.** On a page that leads through several steps, the buttons that move
  through them (back, next, cancel, plan, create) are one grouped toolbar at the bottom right; back is always its
  leftmost button.
- `UX-10` **The actions of a page, top right.** The buttons that act on what the page shows (add a provider, create a
  deployment, edit a list) are one grouped toolbar at the top right of its header. One action of a page is the primary
  one.

## Web ui

- `WEB-1` **List, then detail.** Each kind of thing has a list that leads to a detail view. A list shows one page at a
  time and remembers its page in the address. Creating starts from the list; the actions on a thing are at the top right
  of its detail view.
- `WEB-2` **Detail view.** An overview on top (name, created, updated, the main facts), tabs below; the first tab is
  "Configuration".
- `WEB-3` **No modal dialogs.** Create and edit are pages of their own, with all editable fields at once, saved or
  cancelled. A delete asks inline, in the view itself.
- `WEB-4` **Copy to the clipboard** looks and behaves the same everywhere.

## Android app

- `ANDROID-1` **Material 3, the colors of the phone.** The grocery app follows the light or dark mode of the system and
  takes the colors of the wallpaper where Android has them.
- `ANDROID-2` **One screen at a time.** The deployment, the sign-in, the overview of the lists, one list and a new
  list. The overview has the name of the deployment as its title; at the top right are the button that adds a list and
  the menu with the sign-out. Every other screen of a signed in user has a back arrow at the top left with its own
  title; the back of the system does the same.
- `ANDROID-3` **Pull down to reload.** Every screen that shows data of the deployment loads it again when the user
  pulls it down.

## sakctl window

- `WINDOW-8` **Plan before change.** Create, deploy and destroy show a plan first: what will be created, changed,
  created anew or deleted. The plan of a deploy is the check on the page of the deployment. Only the apply changes
  something, and it shows each resource as it goes, with the output of the provisioning below.
- `WINDOW-9` **A deploy keeps what holds data.** A deploy may create the server anew. The data volume and the addresses
  always stay; a change that would need them created anew stops the plan before anything changes. A deploy happens where
  the deployment is, never at a default location.
- `WINDOW-10` **A destroy says what goes.** It asks only for the password. It keeps the data volume, unless the user
  ticks a box to delete it too; then the form turns red. The plan and a last confirmation dialog say what goes.
- `WINDOW-11` **A redeploy takes over what runs.** With the password at hand, a redeploy fills the empty fields of the
  users the same way in the window and on the command line: the user mode from the labels of the deployment, the admin
  and the whole mail server from its running configuration. What is given wins.

## sakctl command line

- `CLI-1` **A secret is never an argument.** The password of the deployment comes from `SAK_PASSWORD`, or the command
  asks for it on the terminal without echo (`--password` asks even with `SAK_PASSWORD` set); a create asks twice. The
  password at the mail server likewise comes from `SAK_SMTP_PASSWORD` or is typed (`--smtp-password`), the API token of
  the Hetzner Cloud project from `HCLOUD_TOKEN` or is typed (`--hcloud-token`). An argument would show in the list of
  processes.
- `CLI-2` **Test deployments.** `--acme-ca staging` gets the certificates of a deployment from the staging ca of Let's
  Encrypt, which has none of the limits of the production one; the deployment keeps it, sakctl trusts its roots. The
  integration tests use it.
- `CLI-3` **The same as the window.** The command line does what the window does (list, create, deploy, destroy, show
  the running configuration) with the same rules and texts. Without a command, the window opens.
- `CLI-4` **An ssh session, on the command line only.** `sakctl ssh <name>` opens a session as root on the server, `--
  <command>` runs a command there. It runs in the image of the tools like the provisioning: the key derived from the
  password goes to an ssh-agent in the container and is never written, the server is checked against its derived host
  key.
- `CLI-5` **Backup and restore, on the command line only.** `sakctl backup <name>` writes all data of a deployment to
  `~/.sak/<name>/backups/<name>-<UTC time>.age.sak` and shows the path and the size. `sakctl restore <name> <file>`
  replaces the data with that of a backup, after the user types the name of the deployment (`--yes` without asking).
  Both show each step of the server while it runs. Refer to [FDR-007](fdr/FDR-007-backup.md).
- `CLI-6` **Scriptable.** Every input is an argument or an environment variable, so a script types nothing. `--help`
  lists them all.
- `CLI-7` **No surprise by default.** A deploy keeps the settings of the deployment (for example its DNS mode and root
  domain) unless they are given; a default never changes a running deployment.
- `CLI-8` **Exit codes tell what happened.** Success, a general failure, wrong arguments, and each refusal that a script
  may want to handle (a root domain that is taken, mixed DNS modes, a resource that belongs to someone else, data that
  would be lost, a newer version of sakctl for `update --check`) have a code of their own. `--help` lists them. `sakctl
  ssh` ends with the exit code of the command on the server, as ssh does; when sakctl or ssh itself fails, it ends with
  255.
- `CLI-9` **A plan that destroys asks first.** `create`, `deploy` and `destroy` print the plan; when it destroys a
  resource or creates one anew, the command asks for the name of the deployment on the terminal before it applies.
  `--yes` applies without asking, for scripts; without a terminal and without `--yes` it stops with a usage error and
  changes nothing.
- `CLI-10` **One line per event.** Each line is a date, a level and a message, nothing indented. The level has a color
  on a terminal: info green, warning yellow, error red. A warning is what deserves a second look, for example a change
  the plan found or a resource a destroy will delete.
- `CLI-11` **Resource first, the texts of the window.** What happens to a resource reads as the resource, then what is
  done to it, with the same texts as the window: "Server 'alpha': creating…", then "Server 'alpha': created".
