# UI and UX

How the apps look and behave for whoever uses them: the web ui (`sak-frontend`), the wizard
(`sak-wizard`) in its window and on the command line. What the code looks like is in
[AGENT.md](AGENT.md).

## Both apps

- A button or link that creates or adds something starts with the plus sign of `sak-theme`,
  `<i class="sak-icon-plus" aria-hidden="true"></i>` before its text (or alone, with an
  `aria-label`): add a provider, new note, create redirect, the submit of a create form. A button
  creating something of a kind shows the kind after the plus (`+` then the camera for a note with
  a photo). The same sign everywhere, in the wizard and the web ui; tests check it in both
  (`create-buttons.spec.ts`, the test in `sak-wizard/src-tauri/src/main.rs`).

## Web ui (`sak-frontend`)

- Every entity has a paginated list view leading to a detail view; creating is started from the
  list's toolbar, all actions on an entity sit in a toolbar at its top right.
- Every list is paginated with the shared `app-pagination` (`shared/pagination.ts`), `PAGE_SIZE`
  items a page; an entity list keeps its page in the `offset` query parameter. Only choices and
  suggestions (e.g. a select, a datalist) load a whole list, page by page with `fetchAll`
  (`shared/api.ts`).
- Detail view: overview panel on top (name, created, updated, key facts), tabs below, one child
  route each, the first and default one "Configuration".
- No modals: create on `/<entities>/create`, edit on `/<entities>/<id>/edit`, all editable
  attributes at once, saved or cancelled.
- Layout, spacing, forms, buttons, tables, cards, alerts and tabs are bootstrap's classes in the
  templates, its default spacing and corner radius included; a component sets its own layout with
  `host: { class: '…' }`. Bootstrap and its look come from `sak-theme` (`"sak-theme":
  "file:../sak-theme"`, its css listed in `angular.json`): `bootstrap.css` there maps bootstrap onto
  the tokens, `src/styles.scss` only aliases the names the components read (`--wall`, `--ink`,
  `--line`, `--sheet`, `--muted`, `--danger`) to them and keeps the post-it colors. No color literal
  elsewhere. Custom css only for what bootstrap has no class for (the post-its, the terminal look
  of `app-code-block`, the charts, the recording overlay's stop button), in the component's own
  stylesheet.
- Copying to the clipboard is `app-copy-button` (`shared/copy-button.ts`), never written anew.
- The apps follow the browser's `prefers-color-scheme` with no switch of their own (the tokens of
  `sak-theme` flip with it).
- Dates and times in the browser's locale via the `localDateTime` pipe
  (`shared/local-date-time.pipe.ts`), never angular's `DatePipe` or a fixed format.

## Wizard window (`sak-wizard`)

- One view at a time, a page with a header and, when a page has them, actions at the bottom.
  Buttons moving through the wizard (next, back, cancel, plan, the final create) sit in the button
  group at the bottom right (`.page-footer`), buttons acting on the data of the view (add a
  provider, create a deployment) in the one at the top right of the page header.
- A deployment in the list opens its page (its state, location and resources); what acts on it
  (deploy, destroy) sits at the top right there. A deploy runs the plan and the apply of a create
  for a deployment that exists, after its password was checked against the hash on its resources, and
  may create its server anew; the data volume and the addresses stay. A destroy, after the same
  check, removes every resource of the deployment but its data volume; its plan says so first and
  its button is the one `btn-danger` of the wizard.
- The look is bootstrap on `sak-theme` (`bootstrap.css` maps it onto `tokens.css`; the fonts,
  `components.css`), copied into `ui/theme/` by `mise run wizard:theme`; `ui/style.css` holds the
  layout and names no color of its own, every color is a `var(--sak-…)` (a test checks it). The
  toolbars are `btn-group`s, `btn-primary` the one action of a page, `btn-outline-secondary` every
  other button; states are the theme's leds (`.sak-led`, `.sak-status`), not glyphs.

## Wizard command line (`sak-wizard`)

- `sak-wizard list`, `create [<sak name>]`, `deploy <sak name>` and `destroy <sak name>` run on
  the command line; without a command the window opens. The token comes from `--hcloud-token` or
  `HCLOUD_TOKEN`, the name, password and location from the arguments or `SAK_NAME`,
  `SAK_PASSWORD` and `SAK_LOCATION`; `--help` says it all. `mise run wizard:cli -- <command>`
  runs it with the token from pass, `mise run wizard:run -- <command>` the same through tauri dev.
- Every line it prints is `<date> <level> <message>`: one space between each, nothing indented,
  the level coloured (INFO green, WARN yellow, ERROR red on stderr; no colour off a terminal or
  with `NO_COLOR`). WARN is what deserves a second look: a change the plan found, a resource a
  destroy will remove.
- What is done to a resource reads verb first, as it starts and as it is done: `creating`,
  `updating`, `recreating`, `checking` or `destroying server 'alpha'…`, then `created server
  'alpha'` or `server 'alpha' is up-to-date`.
- Before every command the checks of the splash screen run and are logged, `Verifying provider
  tokens…` then `All cloud provider tokens are valid`; a failing one ends the command.
- The output of ansible shows only with `--show-log`, each of its lines prefixed with `[ansible]`.
