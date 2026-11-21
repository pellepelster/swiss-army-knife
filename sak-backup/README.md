# sakbackup

A proposal: the backups of a deployment with [restic](https://restic.net), defined in one YAML file on the server.
sakbackup reads it, runs restic for each backup on its schedule (systemd timers) and keeps the snapshots as the
retention says. [`backup.example.yaml`](backup.example.yaml) is a complete example.

It does not replace `sakadmin backup` (`SAKADM-8`), the one file sakadmin pulls through ssh. It adds backups the server
makes on its own, to repositories the user chooses, without a machine of the user.

## The window

`sakbackup` is a window for now (`src-tauri/`, `ui/`). It signs in at a SAK instance through the browser
and shows the backup plans, the repositories and the snapshots on the left; they are empty for now. `mise run backup:run`
runs it. The architecture is in [components/BACKUP.md](../docs/development/architecture/components/BACKUP.md).

## The file

`/etc/sak-backup/backup.yaml`, owned by root, `0600`. Two maps: the repositories and the backups. A backup names the
repositories it goes to, so one copy of the data can go to several places.

```yaml
version: 1
repositories:
  <name>: <repository>
backups:
  <name>: <backup>
```

`version` is `1`. Names are lower case letters, digits and `-`. A key the format does not know is an error, not
ignored: a typo in `retention` would otherwise keep everything forever.

### Secrets

A secret is never a value in the file, only where to read it:

| Form             | Meaning                                                    |
|------------------|------------------------------------------------------------|
| `{ file: path }` | the content of the file, without the trailing newline      |
| `{ env: NAME }`  | the environment variable of sakbackup                     |

A secret file must belong to root and be readable by root only, else the run fails before restic starts.

### Repository

| Key        | Required | Meaning                                                                         | restic                         |
|------------|----------|---------------------------------------------------------------------------------|--------------------------------|
| `url`      | yes      | the repository, any url restic knows                                            | `--repo`                       |
| `password` | yes      | a secret, the password of the repository                                       | `RESTIC_PASSWORD_FILE`         |
| `env`      | no       | a map of names to secrets or plain values, the environment of restic for it     | environment                    |
| `options`  | no       | a map of extended options                                                       | `-o key=value`                 |
| `limits`   | no       | `upload`, `download` as a rate, e.g. `20MiB/s`                                  | `--limit-upload/-download` KiB |

sakbackup initializes a repository that does not exist yet (`restic init`) on the first backup to it; an existing
repository whose password does not match fails the run, it is never initialized again.

### Backup

| Key            | Required | Meaning                                                                                       |
|----------------|----------|-----------------------------------------------------------------------------------------------|
| `repositories` | yes      | the names of the repositories it goes to                                                      |
| `schedule`     | yes      | `on`: a systemd calendar expression; `jitter`: a duration, `RandomizedDelaySec`              |
| `source`       | yes      | what restic reads, see below                                                                  |
| `host`         | yes      | the host of the snapshots (`--host`), stable when the server is replaced                     |
| `tags`         | no       | the tags of the snapshots (`--tag`)                                                           |
| `lock`         | no       | `file`: a flock it holds for the whole run; `timeout`: how long it waits for it              |
| `prepare`      | no       | a command before restic: `run` (argv), `timeout`                                              |
| `cleanup`      | no       | a command after restic, always, also after a failure: `run`, `timeout`                       |
| `retention`    | no       | what `restic forget` keeps; without it nothing is ever forgotten                             |
| `check`        | no       | `on`: when `restic check` runs; `read_data_subset`: a part of the data it reads too           |
| `overrides`    | no       | by repository name: a `schedule`, `retention` or `check` of its own                          |
| `report`       | no       | `url`, `on` (`failure`, `success`), `headers` (secrets or values): a json report per run    |

`source`:

| Key               | Required | Meaning                                                                             |
|-------------------|----------|-------------------------------------------------------------------------------------|
| `root`            | yes      | the directory restic runs in; the paths of the snapshots are relative to it        |
| `paths`           | yes      | the paths below `root` to back up                                                   |
| `exclude`         | no       | restic patterns (`--exclude`), relative to `root`                                   |
| `one_file_system` | no       | stay on the file system of each path (`--one-file-system`), default `true`         |

`retention`: `keep_last`, `keep_hourly`, `keep_daily`, `keep_weekly`, `keep_monthly`, `keep_yearly` (numbers),
`keep_within` (a duration), `keep_tags` (a list), as the flags of `restic forget` of the same name. Forget is by
`host` and `tags`, so two backups in one repository never forget each other's snapshots. `prune.on` is when
`restic prune` runs, apart from the backups because it is slow and takes an exclusive lock of the repository.

## A run

1. take `lock`, waiting up to its timeout
2. run `prepare`; when it fails, no restic, go to 5
3. for each repository: `restic backup` of `source`, then `restic forget` by `retention`; a repository that fails does
   not stop the others
4. a run that failed for any repository failed
5. run `cleanup`, release the lock, send the `report`

Commands are argv lists, never a shell string. They get `SAK_BACKUP_NAME` (the name of the backup) and
`SAK_BACKUP_ROOT` (`source.root`) in their environment.

## Restore

The file defines backups only. A restore reads the same file for the repositories and their secrets:
`sakbackup snapshots <backup>` lists the snapshots of a backup in each repository,
`sakbackup restore <backup> <repository> [snapshot]` restores one (the newest without a snapshot) into a staging
directory, and `sak-backend restore` takes the data from there as it does for a backup of sakadmin: it checks the
database and the version, stops the deployment, replaces the data and starts it again.

## Open questions

- `sak-backend stage` and `unstage` do not exist yet: the copy `sak-backend backup` makes before it builds its archive,
  split off, so both backups share it.
- Who writes the file: ansible from settings of the deployment (then sakadmin needs a place for the repositories and
  their secrets), or the user on the server.
- Whether the repository password is derived from the password of the deployment like the other keys
  (`SECURITY-1`), so a lost server does not lose the way to the backups.
