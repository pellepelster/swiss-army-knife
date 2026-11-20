# sak-desktop-shared

What the desktop apps of SAK (sakadm, sak-backup) share: the sign-in through the browser, its window, the texts and
the desktop entry of linux. The architecture is in
[apps/BACKUP.md](../docs/development/architecture/apps/BACKUP.md#what-the-desktop-apps-share).

- `cargo test` runs its tests.
- `ui/` is copied into `ui/shared/` of each app by `mise run desktop:ui`.
- `locales/` is generated from `desktop_shared` of `i18n/texts.yml` (`mise run i18n:generate`).
