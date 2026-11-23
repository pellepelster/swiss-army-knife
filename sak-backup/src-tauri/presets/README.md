# Presets of excludes

The excludes a backup plan can start from, one file a preset and a system: `<system>/<preset>.txt`, one
pattern of restic a line, as `--exclude-file` reads them (`#` a comment, `$NAME` a variable). The window
and `sakbackup plan create --preset <preset>` copy the lines of the system the app runs on into a plan,
which keeps them as its own: a change here changes no plan. `core/presets.rs` lists them; its tests check
that each has patterns and that every system has every preset.
