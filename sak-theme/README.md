# sak-theme

The design tokens and the few shared components of sak, as plain css, and bootstrap as the
dependency of this package: a page without a build (sakctl) links them, the angular app
(sak-frontend) can import them, both read the same values and render the same bootstrap. The palette is the one of blcks-theme (`scss/_tokens.scss` there): the light "paper"
surface by default, the dark "terminal" surface when the system prefers dark, the accent green
of the rack-led icon, IBM Plex Sans and Mono.

- `tokens.css` - the custom properties: `--sak-bg`, `--sak-surface`, `--sak-raised`, `--sak-line`,
  `--sak-ink`, `--sak-muted`; `--sak-field` and `--sak-field-line`, the fill and the outline of a field to type in,
  3:1 against the page, the card, the raised fill and the field; `--sak-accent` and `--sak-accent-ink`; the states `--sak-ok`,
  `--sak-info`, `--sak-warn`, `--sak-danger`, `--sak-neutral`; `--sak-danger-text`, danger as
  text on the raised fill (darker in light mode, to be readable); fonts and text sizes
  (`--sak-font-sans`, `--sak-font-mono`, `--sak-text*`); `--sak-radius*`; `--sak-space-1..5`.
- `fonts.css` and `fonts/` - IBM Plex Sans 400/600 and Mono 400/500, the latin subset, under the
  SIL open font license (`fonts/LICENSE`), self-hosted; a consumer loads nothing from outside.
- `bootstrap.css` - bootstrap mapped onto the tokens through its `--bs-*` custom properties, loaded
  after `node_modules/bootstrap/dist/css/bootstrap.min.css` and `tokens.css`; `btn-primary` is the
  accent, the one action of a page, `btn-outline-secondary` every other button; `form-label`,
  `form-control`, `card` and `list-group` take the tokens. `package.json` pins bootstrap,
  `npm ci` here fetches it.
- `components.css` - `.sak-icon-plus` (the plus sign every button that creates or adds something
  starts with, `icons/plus.svg` next to it: a consumer copies `icons/` with the css, a `data:` url
  would break a content security policy of `'self'`), `.sak-led` (a state as a glowing square, `data-sak-state` one of ok, info,
  warn, danger, neutral, busy), `.sak-status` (the led with a word), `.sak-label` (a mono label
  over data), `.sak-mono`.

- `images/` - every image and icon of the apps, once, read from here by each build:
  - `web/` - the favicons and `icons/` (the icons of the apps as svg and png, also for the manifests), copied to the
    root of sak-frontend by its `angular.json`; the docs site takes `favicon.svg`.
  - `sources/` - the sources the png icons are made from, not shipped.
  - `sakctl/` - the icon of Swiss Army Knife and `providers/` (the logo of each cloud provider), copied by
    `mise run cli:theme` into `sak-cli/ui/theme/images/`.
  - `tauri/` - the icons of the sakctl installers and windows, named in `sak-cli/src-tauri/tauri.conf.json`.
  - `android/icons/` - the icons of the android apps (Font Awesome as vector drawables), a res directory of `core`
    and of every app; `android/<app>/` - the launcher icon of the app, a res directory of that app
    (`sak.android.app`).

A consumer's own stylesheet holds no color literal, every color is a `var(--sak-…)`;
sakctl's tests check that for its `ui/style.css`.

## Consumers

- sakctl: `mise run cli:theme` runs `npm ci` here and copies bootstrap and the files into
  `sak-cli/ui/theme/` (not committed, tauri bundles that one directory); `index.html` links
  `theme/bootstrap.min.css`, `fonts.css`, `tokens.css`, `bootstrap.css` and `components.css`
  before its own `style.css`, which holds the layout alone. `cli:run`, `cli:build` and
  `cli:test:unit` copy first. The toolbars are button groups: the one at the bottom right moves
  through the wizard, the one at the top right acts on the data of the view.
- sak-frontend: not wired yet. The way to do it: depend on `"sak-theme": "file:../sak-theme"`
  instead of on bootstrap, list `node_modules/sak-theme/{fonts,tokens,bootstrap,components}.css`
  after bootstrap in the `styles` of `angular.json`, and drop from `src/styles.scss` the
  mapping of bootstrap onto its own palette, which `bootstrap.css` here replaces; the post-it
  colors stay its own.
