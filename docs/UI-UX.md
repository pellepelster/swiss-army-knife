# UI and UX

How the apps look and behave for the people who use them: the web ui, and sakctl in its window and on the command
line. How the system is built is in [ARCHITECTURE.md](ARCHITECTURE.md), how the code does it in
[AGENT.md](../AGENT.md).

## All apps

- **One look.** All apps use the same theme, its colors, fonts and status lights. They follow the light or dark mode
  of the system and have no switch of their own.
- **Only the main create action has a plus.** A page has at most one button with the plus sign of the theme: the one
  that creates what the page is about (a note, a bucket, a redirect, a domain). Other add actions show only what they
  add, as its icon (a photo, a recording); their label and tooltip say what they do. A small inline link to add
  something (`+ label`) may start with a plus in its text.
- **Destructive is red.** A button that destroys, deletes or removes something has the danger color. The button that
  does it is red; a toolbar button that only leads to it is outlined red. No other button is red.
- **Confirm before data goes.** An action that deletes something that holds data or configuration the user made
  asks for confirmation first and says what goes. Things the app makes again by itself, for example a plan or a
  filter, need no confirmation. Cancel is the safe default.
- **Dates and times** are shown in the locale of the user, never in a fixed format.
- **Fields are checked when they lose focus.** A field is checked when the user leaves it, not while typing. The
  error is shown right under the field, in the danger color. While a field is invalid, the action that depends on it
  (next, create, save) is disabled; typing in the field again clears the error until the field is left again. The
  same check runs once more when the action is taken, and on the command line, where an invalid value ends the
  command with an error.

## Web ui

- **List, then detail.** Each kind of thing has a list that leads to a detail view. A list shows one page at a time
  and remembers its page in the address. Creating starts from the list; the actions on a thing are at the top right
  of its detail view.
- **Detail view.** An overview on top (name, created, updated, the main facts), tabs below; the first tab is
  "Configuration".
- **No modal dialogs.** Create and edit are pages of their own, with all editable fields at once, saved or
  cancelled. A delete asks inline, in the view itself.
- **Copy to the clipboard** looks and behaves the same everywhere.

## sakctl window

- **One page at a time.** Each page has a header and, if needed, actions at the bottom. Buttons that move through
  the steps (next, back, cancel, plan, create) are at the bottom right. Buttons that act on the data of the page (add
  a provider, create a deployment) are at the top right of the header. One action of a page is the primary one.
- **Start-up checks.** The splash screen runs the checks and has a Quit button. A failed check shows its reason in
  its own row, never in a dialog. Quit is then the only action left.
- **A deployment and its page.** The list shows each deployment with its state and the link to its frontend; the
  link opens the browser of the system. The page of a deployment shows its state, location and resources, and the
  actions on it (deploy, destroy) at the top right.
- **Plan before change.** Create, deploy and destroy show a plan first: what will be created, changed, created anew
  or removed. Only the apply changes something, and it shows each resource as it goes, with the output of the
  provisioning below.
- **A deploy keeps what holds data.** A deploy may create the server anew. The data volume and the addresses always
  stay; a change that would need them created anew stops the plan before anything changes. A deploy happens where
  the deployment is, never at a default location.
- **A destroy says what goes.** It asks only for the password. It keeps the data volume, unless the user ticks a box
  to delete it too; then the form turns red. The plan and a last confirmation dialog say what goes.
- **A redeploy takes over what runs.** With the password at hand, a redeploy fills the empty fields of the users the
  same way in the window and on the command line: the user mode from the labels of the deployment, the admin and the
  whole mail server from its running configuration. What is given wins.

## sakctl command line

- **A password is never an argument.** The password of the deployment comes from `SAK_PASSWORD`, or the command asks
  for it on the terminal without echo (`--password` asks even with `SAK_PASSWORD` set); a create asks twice. The
  password at the mail server likewise comes from `SAK_SMTP_PASSWORD` or is typed (`--smtp-password`). An argument
  would show in the list of processes.
- **The same as the window.** The command line does what the window does (list, create, deploy, destroy, show the
  running configuration) with the same rules and texts. Without a command, the window opens.
- **Scriptable.** Every input is an argument or an environment variable, so a script types nothing. `--help` lists
  them all.
- **No surprise by default.** A deploy keeps the settings of the deployment (for example its DNS mode and root
  domain) unless they are given; a default never changes a running deployment.
- **Exit codes tell what happened.** Success, a general failure, wrong arguments, and each refusal that a script may
  want to handle (a root domain that is taken, mixed DNS modes, a resource that belongs to someone else, data that
  would be lost) have a code of their own. `--help` lists them.
- **One line per event.** Each line is a date, a level and a message, nothing indented. The level has a color on a
  terminal: info green, warning yellow, error red. A warning is what deserves a second look, for example a change
  the plan found or a resource a destroy will remove.
- **Verb first.** What happens to a resource reads verb first, as it starts and when it is done: "creating server
  'alpha'…", then "created server 'alpha'".
- **The same checks as the window** run before each command; a failed check ends the command.
- **Quiet by default.** The output of the provisioning shows only when the user asks for it.
