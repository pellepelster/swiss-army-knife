# sak-theme

The design tokens and the few shared components of sak, as plain css: a page without a build
(sak-wizard) links them, the angular app (sak-frontend) can import them, both read the same
values. The palette is the one of blcks-theme (`scss/_tokens.scss` there): the light "paper"
surface by default, the dark "terminal" surface when the system prefers dark, the accent green
of the rack-led icon, IBM Plex Sans and Mono.

- `tokens.css` - the custom properties: `--sak-bg`, `--sak-surface`, `--sak-raised`, `--sak-line`,
  `--sak-ink`, `--sak-muted`; `--sak-accent` and `--sak-accent-ink`; the states `--sak-ok`,
  `--sak-info`, `--sak-warn`, `--sak-danger`, `--sak-neutral`; fonts and text sizes
  (`--sak-font-sans`, `--sak-font-mono`, `--sak-text*`); `--sak-radius*`; `--sak-space-1..5`.
- `fonts.css` and `fonts/` - IBM Plex Sans 400/600 and Mono 400/500, the latin subset, under the
  SIL open font license (`fonts/LICENSE`), self-hosted; a consumer loads nothing from outside.
- `components.css` - `.sak-led` (a state as a glowing square, `data-sak-state` one of ok, info,
  warn, danger, neutral, busy), `.sak-status` (the led with a word), `.sak-label` (a mono label
  over data), `.sak-mono`.

A consumer's own stylesheet holds no color literal, every color is a `var(--sak-…)`;
sak-wizard's tests check that for its `ui/style.css`.

## Consumers

- sak-wizard: `mise run wizard:theme` copies the files into `sak-wizard/ui/theme/` (not committed,
  tauri bundles that one directory), `index.html` links `theme/fonts.css`, `theme/tokens.css` and
  `theme/components.css` before its own `style.css`. `wizard:run` and `wizard:build` copy first.
- sak-frontend: not wired yet. The way to do it: add `../sak-theme/fonts.css`, `tokens.css` and
  `components.css` to the `styles` of `angular.json` and let `src/styles.scss` map its palette
  onto the tokens (`--wall: var(--sak-bg)`, `--ink: var(--sak-ink)`, …); bootstrap already takes
  its colors from those custom properties there.
