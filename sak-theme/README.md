# sak-theme

The design tokens and the few shared components of sak, as plain css, and bootstrap as the
dependency of this package: a page without a build (sakctl) links them, the angular app
(sak-frontend) can import them, both read the same values and render the same bootstrap. The palette is the one of blcks-theme (`scss/_tokens.scss` there): the light "paper"
surface by default, the dark "terminal" surface when the system prefers dark, the accent green
of the rack-led icon, IBM Plex Sans and Mono.

- `tokens.css` - the custom properties: `--sak-bg`, `--sak-surface`, `--sak-raised`, `--sak-line`,
  `--sak-ink`, `--sak-muted`; `--sak-accent` and `--sak-accent-ink`; the states `--sak-ok`,
  `--sak-info`, `--sak-warn`, `--sak-danger`, `--sak-neutral`; fonts and text sizes
  (`--sak-font-sans`, `--sak-font-mono`, `--sak-text*`); `--sak-radius*`; `--sak-space-1..5`.
- `fonts.css` and `fonts/` - IBM Plex Sans 400/600 and Mono 400/500, the latin subset, under the
  SIL open font license (`fonts/LICENSE`), self-hosted; a consumer loads nothing from outside.
- `bootstrap.css` - bootstrap mapped onto the tokens through its `--bs-*` custom properties, loaded
  after `node_modules/bootstrap/dist/css/bootstrap.min.css` and `tokens.css`; `btn-primary` is the
  accent, the one action of a page, `btn-outline-secondary` every other button; `form-label`,
  `form-control`, `card` and `list-group` take the tokens. `package.json` pins bootstrap,
  `npm ci` here fetches it.
- `components.css` - `.sak-icon-plus` (the plus sign every button that creates or adds something
  starts with), `.sak-led` (a state as a glowing square, `data-sak-state` one of ok, info,
  warn, danger, neutral, busy), `.sak-status` (the led with a word), `.sak-label` (a mono label
  over data), `.sak-mono`.

A consumer's own stylesheet holds no color literal, every color is a `var(--sak-…)`;
sakctl's tests check that for its `ui/style.css`.

## Consumers

- sakctl: `mise run cli:theme` runs `npm ci` here and copies bootstrap and the files into
  `sak-cli/ui/theme/` (not committed, tauri bundles that one directory); `index.html` links
  `theme/bootstrap.min.css`, `fonts.css`, `tokens.css`, `bootstrap.css` and `components.css`
  before its own `style.css`, which holds the layout alone. `cli:run`, `cli:build` and
  `cli:test` copy first. The toolbars are button groups: the one at the bottom right moves
  through the wizard, the one at the top right acts on the data of the view.
- sak-frontend: not wired yet. The way to do it: depend on `"sak-theme": "file:../sak-theme"`
  instead of on bootstrap, list `node_modules/sak-theme/{fonts,tokens,bootstrap,components}.css`
  after bootstrap in the `styles` of `angular.json`, and drop from `src/styles.scss` the
  mapping of bootstrap onto its own palette, which `bootstrap.css` here replaces; the post-it
  colors stay its own.
