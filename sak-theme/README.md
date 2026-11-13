# sak-theme

The design tokens and the shared components of SAK as plain css, with bootstrap as a dependency. A page without a
build (sakctl) links them, the Angular app (sak-frontend) imports them; both use the same values and the same
bootstrap. The palette is the one of blcks-theme: the light "paper" surface by default, the dark "terminal" surface
when the system prefers dark, the accent green of the rack-led icon, IBM Plex Sans and Mono.

- `tokens.css`: the custom properties.
  - Surfaces and text: `--sak-bg`, `--sak-surface`, `--sak-raised`, `--sak-line`, `--sak-ink`, `--sak-muted`.
  - Fields: `--sak-field` and `--sak-field-line`, with 3:1 contrast against the page, the card, the raised fill and
    the field.
  - Accent and states: `--sak-accent`, `--sak-accent-ink`, `--sak-ok`, `--sak-info`, `--sak-warn`, `--sak-danger`,
    `--sak-neutral`, and `--sak-danger-text` (danger as text on the raised fill, darker in light mode).
  - A terminal, dark in both modes: `--sak-terminal`, `--sak-terminal-bar`, `--sak-terminal-ink`,
    `--sak-terminal-muted`.
  - Fonts, sizes and spacing: `--sak-font-sans`, `--sak-font-mono`, `--sak-text*`, `--sak-radius*`,
    `--sak-space-1..5`.
- `fonts.css` and `fonts/`: IBM Plex Sans 400/600 and Mono 400/500, latin subset, self-hosted, SIL Open Font
  License (`fonts/LICENSE`).
- `bootstrap.css`: maps bootstrap onto the tokens through its `--bs-*` properties. Load it after bootstrap and
  `tokens.css`. `btn-primary` is the accent (the one action of a page), `btn-outline-secondary` is every other
  button. It also styles `form-label`, `form-control`, `card`, `list-group`, `nav-tabs` (the card after the tabs is
  the folder, the active tab takes its fill and line), the alerts and the `text-bg-*` of the states.
  `package.json` pins bootstrap.
- `components.css`: `.sak-icon-plus` (the plus sign of every button that creates or adds; it uses `icons/plus.svg`,
  because a `data:` url breaks a content security policy of `'self'`), `.sak-led` (a state as a glowing square,
  `data-sak-state` is ok, info, warn, danger, neutral or busy), `.sak-status` (the led with a word), `.sak-mono`.
- `images/`: all images and icons of the apps, read from here by each build.
  - `web/`: the favicons and `icons/` (the app icons as svg and png, also for the manifests), copied into
    sak-frontend by its `angular.json`. The docs site uses `favicon.svg`.
  - `sources/`: the sources of the png icons, not shipped.
  - `sakctl/`: the SAK icon and `providers/` (the logo of each cloud provider), copied by `mise run cli:theme`.
  - `tauri/`: the icons of the sakctl installers and windows (`sak-cli/src-tauri/tauri.conf.json`).
  - `android/icons/`: the icons of the Android apps (Font Awesome as vector drawables), a res directory of `core` and
    of each app. `android/<app>/`: the launcher icon of the app.

The stylesheet of a consumer has no color literal, every color is a `var(--sak-…)`. The tests of sakctl check this
for its `ui/style.css`.

## Consumers

- **sakctl:** `mise run cli:theme` runs `npm ci` here and copies bootstrap and the files into `sak-cli/ui/theme/`
  (not committed, tauri bundles it). `index.html` links them before its own `style.css`, which has only the layout.
  `cli:run`, `cli:build` and `cli:test:unit` copy first.
- **sak-frontend:** builds a bootstrap subset from the scss here (`sak-frontend/src/bootstrap.scss`, only the
  components and utilities its pages use), then lists `fonts.css`, `tokens.css`, `bootstrap.css` and
  `components.css` in the `styles` of its `angular.json`. The post-it colors are its own.
