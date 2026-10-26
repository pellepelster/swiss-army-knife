# theme

A copy of the `scss/` of blcks-theme (`blcks/blcks-theme`, commit 73c01f49), the
infra::provision "terminal" design tokens, the Bootstrap configuration built from them and the
custom components. It is copied instead of referenced so the console builds without a blcks
checkout next to this repository; take changes over from there instead of editing it here.

- `_tokens.scss` design tokens, `_config.scss` the Bootstrap variables built from them
- `theme.scss` Bootstrap itself, configured with them
- `components.scss` the custom components (`.label-mono`, `.status`, …), loaded after `theme`
