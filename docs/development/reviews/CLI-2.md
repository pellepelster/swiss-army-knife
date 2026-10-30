---
title: "Review: sak-cli, second pass"
linkTitle: "sak-cli (2)"
description: "The second review of sak-cli: structure, simplifications, smells, bugs."
weight: 2
---

# Review: sak-cli, second pass

**Date:** 2026-10-30
**Scope:** `sak-cli/src-tauri` (Rust: core, cli, gui), `sak-cli/ui` (JS, HTML, CSS), `sak-cli/cloud` (sak-cloud).
**Focus:** code structure, simplifications, code smells, bugs. The first review ([CLI.md](CLI.md)) focused on
security and correctness; its IDs (`S<n>`, `C<n>`, `Q<n>`) stay as they are.
**Commit:** the line numbers refer to `937b513`; `sak-cli` had no uncommitted changes.

The review is read-only. It was done in five slices, one reviewer each; the findings of each slice have their own
prefix, so an ID is unique across the review:

| Prefix | Slice                                                                                       |
|--------|---------------------------------------------------------------------------------------------|
| `CA-`  | sak-cloud: `api/` (registry, diff, context), `hetzner/api/`, `labels.rs`, `providers/`        |
| `CP-`  | sak-cloud: `hetzner/deployment.rs` and the provisioners                                      |
| `CU-`  | core utilities: `keys`, `ssh`, `backup`, `maintenance`, `config`, `update`, `container`, checks |
| `CD-`  | core deployments: `deployments.rs`, `provision.rs`, `ansible_roles.rs`, `roles.rs`, `events.rs` |
| `UI-`  | `cli.rs`, `gui/`, `ui/`, the locales                                                          |

Each finding has a severity: **High** (wrong result or data at risk in a realistic scenario), **Medium** (a bug on a
less common path, or a smell that hides bugs), **Low** (a smell or a simplification). Findings marked *(confirmed)*
were checked a second time against the code after the slice reviewer reported them; the others rest on the slice
reviewer's reading. A finding that is fixed is ~~struck through~~ and marked **fixed**; the commit that fixes it
names its ID.

## Summary

| Slice                          | High | Medium | Low | Structure |
|--------------------------------|-----:|-------:|----:|----------:|
| CA sak-cloud api and registry  |    1 |      5 |  11 |         4 |
| CP sak-cloud provisioners      |    1 |      5 |  12 |         5 |
| CU core utilities              |    1 |      5 |  13 |         3 |
| CD core deployments            |    1 |      6 |  14 |         5 |
| UI command line, window, pages |    1 |      7 |  15 |         3 |
| **Total**                      | **5** | **28** | **65** |    **20** |

### Fix first

1. ~~**UI-1** the page can deploy another deployment than the one shown (`CheckedDeployment` has no identity).~~
2. ~~**CP-1** one odd deployment (a kept volume after a destroy) hides the whole list~~; **CA-1** one unknown
   server type does the same.
3. **CD-1 / UI-6 / UI-5** a multi-user redeploy while the backend is down fails with "invalid smtp host" and
   command-line instructions in the window.
4. **CU-1** a backup or restore hangs forever on a dropped connection; **CU-3 / CU-4** ssh's and the lock's exit
   codes are blamed on `sak-backend`.
5. **UI-2 / UI-3** a destroy runs after Cancel; "delete the data volume" stays ticked.
6. **CD-2** a destroy plan is refused for a volume it would never recreate.
7. **UI-4** a deployment without a location cannot be checked in the window.

### The structural threads that run through all slices

- **One thing written twice (or five times).** The maintenance flow (UI-11), the deploy completion (UI-12), the
  checks (UI-13), "find the deployment and verify the password" (CD-11), the `sak-backend` driver (CU-12), the
  request plumbing of the api (CA-9), the provisioner skeleton (CP-12). The cure is the same each time: the logic
  goes into core or into one generic, the two frontends keep their sinks.
- **Plan and apply disagree.** The registry refuses duplicates and owner-less recreates only in the apply (CA-2,
  CA-3), a destroy plans with the create registry (CD-2), the playbook decision differs (CD-4), a pending label
  change reads as a recreate (CP-3). Making the plan the one gate and letting the registry tell what it applied
  resolves all of them (CD-25, CP-22).
- **Errors that lose their cause.** `.ok()` on the running config (CD-1), stderr thrown away when the markers are
  read (CD-6), exit 255 as a script code (CU-3), a `state()` that fails the list (CP-1, CA-1). Typed errors and total
  functions where a list is shown.
- **Booleans and strings standing in for enums.** `DiffItem` (CA-8), the plan vocabulary (CD-13, UI-17), the
  reasons (CU-14), the intents on `NewDeployment` (CD-14), a dozen boolean parameters (CU-13, UI-16, CP-17).
- **Three files that have outgrown themselves.** `core/deployments.rs` (CD-22), `cloud/hetzner/deployment.rs`
  (CP-20), `cli.rs` (UI-24).

---

## CA: sak-cloud, api and registry

### Bugs

#### CA-1 (High, confirmed): A server of an unknown type or location fails every list of deployments

`hetzner/api/model.rs:12` (`Location`) and `:30` (`ServerType`) and `hetzner/api/servers.rs:11` (`ServerStatus`)
are closed enums without a `#[serde(other)]` fallback; only `hetzner/api/error.rs:33` has one. `Server.server_type`
and `location` are required, and `list_all` deserializes a whole page strictly. **Scenario:** the project holds one
server of a type the enum does not know (an older generation, or a type Hetzner adds after this release) →
`deployment::discover` (`hetzner/deployment.rs:410`) and `Listable for HetznerServerProvisioner`
(`hetzner/server/provisioner.rs:166`) fail → sakctl cannot list deployments at all. Same shape, lower likelihood:
`VolumeStatus` (`volumes.rs:18`), `RRType` (`zones.rs:156`). **Fix:** `Named<String>` (or an `Unknown(String)`
variant) for what comes back from Hetzner; keep the typed enums for requests.

#### CA-2 (Medium, confirmed): A recreate without owner labels passes the plan and fails half way through the apply

`api/registry.rs:313` runs the plan so that nothing changes when it fails, but the owner check `self.owner()?` sits
only in the recreate branch of the apply at `:339`. **Scenario:** a registry without `owned_by` (a library user;
`deployment.rs` does set it), a recreatable server that needs a recreate → `diff_all` passes, key, volume, ips and
firewall are applied, the server fails with "nothing is destroyed without the owner labels". **Fix:**
`refuse_recreate` (`:203`) also requires the owner when `needs_recreate()`.

#### CA-3 (Medium, confirmed): A `Duplicate` passes the plan and fails during the apply

`diff_all` (`registry.rs:261-297`) refuses only recreates; `DiffStatus::Duplicate` becomes an error only in
`apply_all_with` at `:329-334`, after earlier resources were applied. In the deployment the ssh key is first in the
order, so nothing precedes it today, but the registry's own contract ("never stops half way") does not hold for any
other order. `diff::log_text` (`api/diff.rs:196-213`) prints neither `Duplicate` nor `ParentMissing` resources, so
a plan report can hide the thing that fails. **Fix:** refuse `Duplicate` in `diff_all`; print every status.

#### CA-4 (Medium): A destroy has no plan phase, a `NotOwned` resource stops it with dependents already gone

`registry.rs:366-387` looks each resource up only as it destroys it, in reverse order. **Scenario:** the server is
deleted, then the firewall of the name belongs to someone else → `NotOwned` → ips, volume and key remain; a second
destroy stops at the same firewall. **Fix:** run `check_owned` on every resource before the first delete, as
`apply_all_with` runs `diff_all` first.

#### CA-5 (Low, confirmed): `unreachable!` is reachable with zero attempts

`Backoff::new` is `pub` and `with_retry`/`with_wait` (`hetzner/api/mod.rs:130-138`) accept any value; `send`
(`:365`) and `wait_for_action` (`:306`) then panic instead of returning an error. **Fix:** `attempts: NonZeroU32`,
or clamp in `Backoff::new`.

#### CA-6 (Low): A parent that is itself `ParentMissing` or `Duplicate` counts as present

`registry.rs:267-271` marks a child `ParentMissing` only when a dependency's diff is `Missing`. **Scenario:** the
ssh key is missing → the server is `ParentMissing` → the roles (which depend on the server) are diffed anyway, and
their lookups of the server return `None`. Each provisioner guards this itself today
(`dns_record/provisioner.rs:107`, `src-tauri/src/core/ansible_roles.rs:197`), which makes the registry's check
half-redundant. **Fix:** include `ParentMissing | Duplicate` at `:270`, or make it the provisioner's job alone.

#### CA-7 (Low, suspected): A user's label with a numeric `_n` suffix is folded on read

`hetzner/labels.rs:142-147` `key_of` turns a raw `foo_1` into `foo`; `labels()` (`:71-80`) concatenates the values.
`add` refuses `_` on the write side only. A live resource carrying someone's `backup_1=a`, `backup_2=b` would show
as one label `backup` in the diff. **To confirm:** whether a runtime's `labels()` is built through
`from_raw(..).labels()` (`server/provisioner.rs:185` does for the hash labels) or compares raw labels
(`hetzner/mod.rs:79` does), which would limit the impact to `hash_matches`.

### Smells

#### CA-8 (Medium): `DiffItem` is three booleans standing in for an enum

`api/diff.rs:9-12` has `changed`/`triggers_recreate`/`missing`; a removed label (`:59-68`) is `changed: true` with
no values, so its text (`:97-99`) reads "label 'd' changed" when it was removed (the test at `:283` asserts this). A
`Kind { Changed, Recreate, Missing, Removed }` removes the invalid states and the four-way `if` in `log_text`.

#### CA-9 (Medium): Seven copies of the request plumbing

Each api file repeats `get::<XResponse>(..).map(|r| r.x)`, `put::<XResponse,_>(..).x`,
`post::<ActionResponse,_>(..).action`, `get_by_name = list(name).next()` (`ssh_keys.rs:58`, `servers.rs:154`,
`volumes.rs:91`, `primary_ips.rs:122`, `firewalls.rs:211`), `change_protection` four times, `wait_for_action` six
times. Only the wrapper key varies. See CA-19.

#### CA-10 (Low): `post(path, Option<&B>)` with `NO_BODY: Option<&Empty>`

`hetzner/api/mod.rs:179-190, 407-411`: an optional body makes every call site spell `Some(&request)`. Split into
`post` and `post_empty`.

#### CA-11 (Low): `send` rebuilds the request to learn its method

`mod.rs:316-324`: `try_clone().and_then(build)` just to read the `Method`. The callers know it; pass it in.

#### CA-12 (Low): `Lookup::depends_on` is dead

`api/lookup.rs:26`: never overridden, never called (the registry reads `Resource::depends_on`); the doc says so.

#### CA-13 (Low): `ProviderManager`, `ProviderConfiguration` and `validate` serve only the example

sakctl constructs `HetznerProviderRuntime::from_api` everywhere (`src-tauri/src/core/deployments.rs:1062, 1271,
1392, 1444`) and checks tokens itself. `validate` (`providers/hetzner.rs:116-129`) lists every page of ssh keys to
test a token and wraps the `ApiError` into `Error::Message`, losing the code. `#[async_trait]` on `ProviderRuntime`
(`providers/mod.rs:20`) has no async method.

#### CA-14 (Low): `HetznerProviderConfiguration` derives `Debug` with a public `token`

`providers/hetzner.rs:18-21`. Nothing prints it today; one `{:?}` away from a token in a log. A newtype with a
redacting `Debug` (compare S10 of the first review).

#### CA-15 (Low): `Listable` is implemented six times for one test

`api/provisioner.rs:17`, used by `tests/hetzner_dns.rs` only. It lists the whole project without a label filter,
which is what makes CA-1 bite in `server/provisioner.rs:166`.

#### CA-16 (Low): Stringly typed errors and states

`Error::Message` for "declared twice", "no provisioner", "depends on itself", "nothing destroyed without owner"
(`registry.rs:243, 246, 431, 225`); the tests match substrings. The cycle message says "depends on itself" for an
indirect cycle a→b→a. `Zone.status: String` (`zones.rs:38`) lists its values in a comment.

#### CA-17 (Low): A test that does not test its name

`hetzner/api/tests.rs:404-413` `a_read_is_sent_again_after_a_connection_error` asserts only that the result is an
error; nothing counts attempts, so it passes with zero retries. `tests.rs:507-511` adds an `impl HetznerApi` to call
a `pub(crate)` method the child module could call already.

#### CA-18 (Low): Small things

- `LookupProviders::mark_pending`/`clear_pending` are `pub` (`api/context.rs:120-126`) on an exported type; only
  the registry should drive them.
- `label_diff` collects both maps into `Vec`s and does a linear `find` (`api/diff.rs:224-232`) although `Labels`
  is a `BTreeMap`.
- The chunk threshold is off by one (`labels.rs:102`): `>=` 62 chunks a 62-character value, though 63 fits a
  Hetzner label and the comment at `:8-9` says 63.

### Structure

#### CA-19: One generic `ResourceApi<T>` for the six CRUD apis

A `trait HetznerResource { const PATH; const KEY; type Create; type Update; }` with a generic
`list/get/get_by_name/create/update/delete/change_protection/wait_for_action` removes most of `servers.rs`,
`volumes.rs`, `primary_ips.rs`, `firewalls.rs`, `ssh_keys.rs`; a new kind is a type and its action methods. The
`list_page!` macro does half of this for pages already. Resolves CA-9, CA-10, CA-11.

#### CA-20: The apply diffs everything twice

`apply_all_with` runs `diff_all` and then re-diffs each resource before applying it (`registry.rs:313, 321`); the
server diff alone does five lookups. The second diff is needed only for resources with a pending dependency. Keep
the plan's `ResourceDiff` and re-diff only when `depends_on` intersects the pending set.

#### CA-21: `pending` as a per-run value, `diff_all` takes `&self`

`diff_all` needs `&mut self` only for `pending`. A `DiffRun<'a> { providers: &'a LookupProviders, pending }` that
implements `DiffContext` makes `diff_all` `&self`, removes `mark_pending`/`clear_pending` from the public surface
(CA-18), and a plan cannot leak pending state into the next run.

#### CA-22: The erased layers are duplicated

`api/context.rs:57-89` and `api/registry.rs:18-128` each have an `Erased<_, _>` with an `expect`ed downcast. One
`ErasedProvisioner` that also implements `ErasedLookupProvider` (a `Provisioner<R>` is a
`LookupProvider<R::Lookup>` already) halves that code.

---

## CU: core utilities

### Bugs and security

#### CU-1 (High, confirmed): A backup or restore hangs forever on a dropped connection

`ssh.rs:65-70` sets `ConnectTimeout=30` only; no `ServerAliveInterval`/`ServerAliveCountMax`. `run_streaming`
(`ssh.rs:236-293`) blocks in `io::copy` until ssh exits. The maintenance wraps this in `limited`
(`maintenance.rs:108-119`); `BackupManager::backup/restore` are called bare (`cli.rs:919`, `cli.rs:970`).
**Scenario:** the network drops mid-backup → ssh waits for the TCP keepalive (hours), sakctl shows nothing, the
`.partial` file stays. **Fix:** `-o ServerAliveInterval=15 -o ServerAliveCountMax=4` (and `BatchMode=yes`) in
`ssh_arguments`; a `limited` around backup and restore.

#### CU-2 (Medium, confirmed): A maintenance timeout stops every tracked run, not its own session

`maintenance.rs:115` calls `provision::stop_runs()`, which drains the global `RUNS` and removes all containers and
run directories (`provision.rs:444-455`). The window cannot start a deploy and a maintenance at the same time today
(the run view has no way back while it runs), so this is a latent flaw: `limited` should stop its own
`SessionContainer`, which needs a handle from `run_streaming`. The same `stop_runs` from an async context runs
`docker rm --force` synchronously on a tokio worker (`provision.rs:472-478`).

#### CU-3 (Medium, confirmed): ssh's own failure is reported as the script's exit code

ssh exits 255 on a host-key mismatch, a refused key, a reset. `backup::ended` (`backup.rs:204-209`) and
`maintenance::answer` (`maintenance.rs:290-294`) turn that into "sak-backend on the server ended with exit code 255";
the cause went by as a stderr line. **Scenario:** the server was recreated by someone else → "Host key
verification failed." scrolls past, the error blames sak-backend. **Fix:** map 255 to an ssh error that carries the
last stderr line.

#### CU-4 (Medium, confirmed): The busy exit code is handled for the maintenance only

The script's `lock()` (`ansible/roles/backend/templates/sak-backend.j2:35-41`) guards backup, restore, update and
reboot and exits 2 for all of them. `maintenance.rs:15, 287` maps it to `shared.maintenance.busy`; `backup.rs:204-209`
has no such arm, so `sakctl backup` during an update says "ended with exit code 2". Same root cause as CU-3: two
interpreters of one script's exit codes. See CU-20.

#### CU-5 (Medium, confirmed): The self-update has no architecture in the platform and checks nothing about the binary

`PLATFORM` (`update.rs:27-32`) is `linux|macos|windows`; `verify` (`update.rs:297-312`) proves integrity only. The
release builds macOS universal, Linux and Windows x86_64 only (`mise.toml`). **Scenario:** a release-versioned
sakctl on aarch64 Linux downloads the x86_64 zip, the checksum matches, `self_replace` installs a binary that does
not run. Low likelihood for official releases, cheap to guard: the architecture in the asset name, or a check of
the ELF/Mach-O machine. Downgrades, partial writes and symlinked install paths are handled correctly.

#### CU-6 (Low, confirmed): The first download url is not pinned to GitHub, only the redirects are

`Updater::get` (`update.rs:226-229`) checks only the `https://` prefix of `browser_download_url` from the release
json, while `follow_to_github` (`update.rs:282-294`) restricts the redirect hops to GitHub hosts. Apply `is_github`
to the first host too (within the accepted risk "release not signed", but the code claims a policy it half
enforces).

#### CU-7 (Low): The private client key transits the docker daemon

`exec_with_input` (`ssh.rs:81-114`) pipes `private_key()` into `docker exec -i … ssh-add -`. `docker_api()` supports
`DOCKER_HOST=tcp://` without TLS (`container.rs:213-215`); then the key crosses the network in clear and the agent
holding it lives on the remote host. Either refuse `tcp://` without `DOCKER_TLS_VERIFY` for ssh sessions, or add it
to the accepted risks.

#### CU-8 (Low): The startup checks have no timeout

`canary` (`container.rs:87-112`) and `pull` (`container.rs:129-161`) go through `output()` (`container.rs:294-303`),
which waits forever. A daemon that accepts the connection but never answers hangs the splash screen. The bollard
path has `API_TIMEOUT_SECONDS`; the command-line fallback has nothing.

#### CU-9 (Low, confirmed): The boundary test lists its files by hand and omits the ones that would fail it

`core_knows_neither_tauri_nor_clap` (`mod.rs:48-78`) omits `ssh.rs`, `events.rs` and `deployments/*.rs`. `ssh.rs:180`
has "tauri" in a comment, `deployments/window_fixtures.rs` too. **Fix:** walk `src/core` in the test; fix the
comments.

#### CU-10 (Low): `RunningSmtp` serializes its password

`config.rs:26-40` derives `Serialize` with `password: Option<String>`. Nothing serializes it today (`cli.rs` prints
"set"/"-"), one `print_json(&config)` away from stdout. `#[serde(skip_serializing)]` costs nothing.

#### CU-11 (Low): No zeroization of secrets

`normalized(password)` (`keys.rs:36-38`), the master-key cache (`keys.rs:43`), `private_key()` as a `String`
(`keys.rs:105`), provider tokens (`providers/mod.rs:65-68`) are plain heap data. `age` already pulls in `secrecy`.

### Smells

#### CU-12 (Medium): `backup.rs` and `maintenance.rs` are one driver in two files

`SCRIPT` and `ended()` live in `backup.rs` (`:18, :204`), `BUSY` in `maintenance.rs` (`:15`); maintenance imports
both from backup (`maintenance.rs:6, 294`) and reuses `shared.backup.no_script`/`shared.backup.failed` for
non-backup commands. `ansible_roles.rs:150-165` is a third `run_streaming` caller with its own untranslated
"ssh ended with exit code". Both build `[SCRIPT, cmd, args…]` as `Vec<String>` by hand (`backup.rs:154, 197`,
`maintenance.rs:272-275`). See CU-20.

#### CU-13 (Low): Boolean parameters

`reboot(.., if_pending: bool, ..)` (`maintenance.rs:93-104`), `ssh_arguments(.., terminal: bool, ..)`
(`ssh.rs:45-51`), `client_builder(https_only: bool, ..)` (`config.rs:217`) although a `Transport` enum exists and
is converted back to a bool at `config.rs:174, 277`.

#### CU-14 (Low): Stringly typed reasons

`reasons: Vec<String>` matched against `"kernel"`/`"reboot-required"` (`maintenance.rs:152-160`); the script emits
exactly those two (`sak-backend.j2:82-86`). An enum with `#[serde(other)] Other` catches typos at the type.

#### CU-15 (Low): Five copies of hex encoding, three of random bytes

Hex: `keys.rs:86`, `providers/hetzner.rs:91`, `config.rs:109-112`, `update.rs:303-306`, `provision.rs:557`. Random:
`hetzner::random<N>` (`hetzner.rs:85`), `provision::random_hex` (`provision.rs:554`), `events::random_token`
(`events.rs:183`).

#### CU-16 (Low): `BackupManager` is a unit struct with `&self` methods

`backup.rs:32, 139, 187`, called as `BackupManager.backup(...)`; the receiver carries nothing. The doc line at
`backup.rs:16-17` is duplicated.

#### CU-17 (Low): A timing-based test

`the_master_key_is_derived_once` (`keys.rs:519-530`) asserts ten hkdf calls take less than one 256 MiB argon2;
under a loaded CI this can flake. Count the argon2 invocations instead.

#### CU-18 (Low): `block_on` hides in `container.rs`

`container.rs:116-125`, used by `checks/provider_tokens.rs:2`; it panics when called from an async task and relies
on a comment. `pull()` discards the bollard error silently (`container.rs:130`), so the user never learns why the
progress disappeared.

#### CU-19 (Low): Small things

- `build.rs:62-64` `collect` uses `.flatten()` on `read_dir` while every other failure `expect`s; an unreadable
  file would change a role digest silently.
- `run_streaming(.., command: &[String], ..)` joins the elements with spaces for the remote shell (`ssh.rs:76`);
  internal callers pass safe tokens, `roles::read_command()` is one shell string already. A `command: &str`
  signature or a doc note keeps someone from passing a filename with a space.

### Structure

#### CU-20: One `core::sak_backend` module

`enum Command { Backup { recipient }, Restore, RebootPending, Update, Reboot { if_pending } }` with `fn argv(&self)`,
one `run(runtime, target, command, stream, on_line) -> Result<Outcome, Failure>` where `Failure` is typed
(`Ssh { code, last_line }`, `NotInstalled`, `Busy`, `Failed { error }`, `ExitCode(i32)`). `backup.rs` keeps the file
and age work, `maintenance.rs` the typed answers and their lines; `ansible_roles.rs` uses the same runner. Resolves
CU-3, CU-4, CU-12 and the cross-import.

#### CU-21: A cancellable ssh session per call

`run_streaming` returns or takes a handle that removes its own `SessionContainer`; `limited` stops that handle
instead of `stop_runs()`. With `ServerAlive*`/`BatchMode` in `ssh_arguments` and a `limited` around backup and
restore this resolves CU-1, CU-2.

#### CU-22: Smaller moves

- Split `container.rs`: runtime detection, canary and `command()` stay; `PullProgress` and the bollard pull go to
  `container/pull.rs`; `block_on` to a small `core::runtime`.
- One `core::encoding` (or `keys::hex`/`keys::random`) for hex and random bytes (CU-15).
- The boundary test walks `src/core` instead of a list (CU-9).

---

## CP: sak-cloud, deployment and provisioners

### Bugs

#### ~~CP-1 (High, confirmed): One odd deployment fails the whole list of deployments~~ **fixed**

`hetzner/deployment.rs:800-812` `declared_by` passes `""` as the public key when the deployment has no ssh key;
`hetzner/ssh_key/provisioner.rs:87` `fingerprint("")?` fails, so `deployment.rs:561` `diff_all()?` fails and
`state()` returns `Err`; `src-tauri/src/core/deployments.rs:1071-1073` propagates it with `?` for every discovered
deployment. **Scenario:** a destroy without `delete_data` keeps the volume with its name label → the next listing
says "invalid ssh public key" and shows no deployment at all. The same path hides every deployment when one has
resources in two locations (`:802-805`), a `NotOwned` resource of its name, or a `RecreateRefused` on its volume
(`refuse_recreate` inside `diff_all`, CA-2). **Fix:** `state()` is total, an error per resource or deployment; an
empty declared key diffs as unknown instead of failing. See CP-21.

#### CP-2 (Medium): A server apply can leave the server powered off; an ip it already has is not unassigned

`hetzner/server/provisioner.rs:118-148` `assign` powers the server off (`:134`), unassigns the *declared* ip from
wherever it is (`:137`), but never the ip the server has now (`runtime.ipv4_id`, which the diff at `:251-259` saw
differs). Suspected: Hetzner refuses a second ipv4/ipv6 on a server. On any error after `power_off` nothing powers
the server on again; the `power_on` at `:357` is reached on success only. **Scenario:** a server created without
the primary ips (an older sakctl, or by hand) → the deploy ends with an api error and the server off.

#### CP-3 (Medium): The plan reports a recreate of the server on any change of a dependency, a label change too

`hetzner/server/provisioner.rs:193-200`: any pending ssh key → `DiffItem::recreate("ssh keys", "<known after
apply>")`; `hetzner/dns_record/provisioner.rs:121-135` the same for the ips. Pending means "not UpToDate"
(`api/registry.rs:291-292`), so a key or ip that needs only a label update (acme-ca or user-mode after a partial
apply) makes the state show "ssh keys … requires recreate" on the server and "<known after apply>" on four
records; the apply then only updates labels. **Fix:** pending carries whether the change alters what dependents
read. See CP-22.

#### CP-4 (Medium): A server whose image Hetzner deleted is recreated by the next deploy without a decision

`hetzner/server/runtime.rs:40` maps a null image (`api/servers.rs:49`) to `"unknown"`; `server/provisioner.rs:230`
treats `resource.image != runtime.image` as a recreate; the server is recreatable (`deployment.rs:1040`). Same
mechanism with the type: sakctl declares `server_type_for(location)` (`src-tauri/src/core/deployments.rs:558-563`),
not the existing type, so a server upgraded in the console is silently recreated with the smaller type. The data is
on the volume, but a recreate of a running server should not follow from "unknown": compare only when the image is
known, or make the recreate a confirmed step.

#### CP-5 (Medium): A user's own label on a resource is a diff and is wiped by the apply

`hetzner/mod.rs:71-80` ignores only managed and info keys; `api/diff.rs:283-287` reports every undeclared live
label as removed; every apply sends `labels: Some(resource.labels.clone())` (`volume/provisioner.rs:133-141`,
`primary_ip:146-156`, `firewall:120-123`, `ssh_key:142-150`, `server:337-341`, `dns_record:183-187`). ARCHITECTURE
"Own prefix" says user labels are never under `sak.pelle.io/`, which only makes sense when keys outside the prefix
are left alone. **Scenario:** `env=prod` added in the console → state "Changed", the deploy removes it. Decide once
(CP-23).

#### CP-6 (Low): Growing the data volume is a recreate, which is refused

`hetzner/volume/provisioner.rs:80-86`: declared size > live → `DiffItem::recreate("size")`; the volume is never
recreatable (`deployment.rs:1037-1046`) → `RecreateRefused`. Hetzner grows a volume in place
(`volumes/{id}/actions/resize`; `api/volumes.rs` has no such call). Raising `data_volume_gb` from 16
(`deployment.rs:771`) in a release would refuse the deploy of every existing deployment. Latent until then.

#### CP-7 (Low, suspected): The volume is used while still `creating`

`volume/provisioner.rs:120` `create(..)?.into()` ignores the create action (`api/volumes.rs:95` says the api
continues in an action); `change_protection` at `:124` and the server create with the volume id then depend on the
423 retry of the api (`api/mod.rs:310-312`, 5 attempts ≈ 30 s). `server/provisioner.rs:410-422` `wait_for_volumes`
checks `volume.server` only, never `status`.

#### CP-8 (Low): A server mid-deletion counts as present

`server/provisioner.rs:46-57` returns a server with `ServerStatus::Deleting` (`api/servers.rs:17`); the diff
compares it, the apply updates its labels (`:341`), the roles would ssh into it; a destroy sends a second delete.
Treat `Deleting` as missing and wait, as `destroy` does at `:371-377`.

#### CP-9 (Low): Two power cycles when both ips need assigning

`server/provisioner.rs:343-351` calls `assign` once per ip; each powers off and on (`:132-146`) with the stale
`server.status` from before the first cycle.

#### CP-10 (Low): Duplicates are handled differently per kind

`deployment.rs:413-446`: a second server, key or firewall with the name label is dropped with a warning;
`:448-457` every volume and ip is collected; `declared_by:835` takes `volumes.first()` as the data volume, so a
second labelled volume listed first sets the declared size (then CP-1). Only the ssh key knows `Duplicate`
(`ssh_key/provisioner.rs:90-109`); with the server present and its key renamed, the server diff fails with "ssh key
not found" (`server/provisioner.rs:63-69`) instead of the duplicate message.

#### CP-11 (Low): A firewall selector the parser does not know fails the whole plan

`hetzner/firewall/runtime.rs:19-25` splits on `,` and `=`; a valid selector with `!=`, `in (…)` or a bare key makes
`find` fail with "invalid label selector", and with it the diff, state and apply of the deployment. Several
selectors merge into one map (`a=1` + `a=2` collapse).

### Smells

#### CP-12 (Medium): The seven provisioners repeat one skeleton, about 500 near-identical lines

`find` = get_by_name → map → `identity::check`, each with a pointless `let found: Result<_> = { Ok(..) }; found?`
block (`server:48-56`, `volume:26-34`, `primary_ip:31-39`, `firewall:29-36`, `ssh_key:47-55`, `dns_zone:35-37`);
identical `LookupProvider` impls (7×); identical `Listable` impls (6×, CA-15); apply = find-or-create →
unconditional name+labels PUT → find again; identical `with_labels/with_identity/with_info` builders on six
resources; identical `{name, identity}` lookup structs (5 × 40 lines). What differs per kind: the endpoint and its
create/update types, the `DiffItem`s, the pre-destroy steps (unprotect, detach, unassign, remove_from), the
post-create wait, dependency resolution (server, dns record), the duplicate check (ssh key). See CP-19.

#### CP-13 (Low): The apply always writes

The name+labels PUT runs on every apply, also when only protection, rules or values changed and right after a
create that set them (`server/provisioner.rs:337-341`, `volume:133-141`, `primary_ip:146-156`, `firewall:120-123`,
`ssh_key:142-150`, `dns_record:183-187`). Doubles the calls; `Applied::Updated` says nothing about what changed.

#### CP-14 (Low): `DeploymentSpec` has two roles

A declaration (`new`/`with_*`) and a spec reverse-engineered from labels (`declared_by`, `:800-838`, with
`user_data_hash` and the `UserData::Hash` escape that cannot create: `server/resource.rs:16`,
`server/provisioner.rs:297-302`). `Option` fields meaning "none for a destroy" (`deployment.rs:758-767`) encode the
intent as nullability.

#### CP-15 (Low): Duplicated and unnecessary code

`deployment.rs:595-599` re-implements `DiscoveredDeployment::location()` (`:91-121`), silently taking the first;
`deployment.rs:569` `&diff.resource.name.clone()` clones to borrow; `server/runtime.rs:25-31` `ssh_user`/`ssh_port`
constants on a runtime.

#### CP-16 (Low): Tests

`server/provisioner.rs:638-660` `volumes_and_the_primary_ip_are_compared_by_id` declares no ipv4 lookup, asserts
only volumes, and starts an unused `MockServer` (`:647-648`); `deployment.rs:1303` `let _ = sha256_hex;` silences
an unused import; `deployment.rs:1234, 1338-1342` assert serde and parse behaviour inside state and dns-mode tests.

#### CP-17 (Low): Stringly typed values and boolean parameters

`dns_zone/runtime.rs:11` `status: String`; `deployment.rs:762-764` `user_mode: Option<String>` ("single"/"multi"),
`acme_ca: Option<String>` next to the `DnsMode` enum; `destroy_registry(provider, delete_data: bool)` (`:1058`).

#### CP-18 (Low): What is in `deployment.rs` that belongs elsewhere

About 1030 non-test and 1220 test lines. `RootDomain`/`DomainUse`/`RootDomainConflict`/`root_domains`
(`:156-266, 352-362`) and `deployment_records`/`foreign_records`/`stale_records`/`remove_records` (`:268-350`) are
dns-zone bookkeeping; `DnsMode`, `base_domain`, `frontend_host` (`:364-402`) are naming; `set_server_labels`
(`:672-697`) is an api helper; `check_name`/`MAX_NAME_LEN` (`:700-736`) are validation;
`ResourceKind`/`ResourceState`/`DeploymentState`/`state`/`detail` (`:475-670`) are a presentation of a plan.

### Structure

#### CP-19: A `HetznerKind` trait with one blanket `Provisioner`

`fetch(api, name)`, `compare(resource, runtime, ctx) -> Vec<DiffItem>`, `create`, `update` only when changed,
`prepare_destroy`, `delete`, an optional `duplicate_of`; one blanket `Provisioner` impl does find + identity,
missing/duplicate, the label diff, the waits and the find after the apply. Volume, primary ip, firewall, ssh key
and dns zone fit it and lose about 400 lines; the server keeps its own impl (dependencies, assign, detach waits).
One generic `NamedLookup<R>` replaces the five lookup structs. Resolves CP-12, CP-13.

#### CP-20: Split `deployment.rs`

`deployment/discover.rs` (DiscoveredDeployment, discover), `deployment/spec.rs` (DeploymentSpec, registries, names,
check_name), `deployment/state.rs` (ResourceKind/State, state, detail), `dns/root_domain.rs` (RootDomain, DnsMode,
base and frontend host), `dns/records.rs` (deployment/foreign/stale/remove records); `set_server_labels` next to
the server api. Resolves CP-18.

#### ~~CP-21: Make `state()` total~~ **fixed**

A per-resource `Error` status instead of propagating, so one broken deployment cannot hide the list (CP-1).

#### CP-22: Pending carries whether the dependency's value changes

A fingerprint recreate: yes; a label change: no (CP-3). The volume size recreate becomes an in-place resize (CP-6).

#### CP-23: Decide label ownership once

Either ignore and keep keys outside `sak.pelle.io/` in `hetzner_label_diff` and in every update (CP-5), or change
the ARCHITECTURE sentence.

---

## CD: core deployments and provisioning

### Bugs

#### CD-1 (High, confirmed): A multi-user deploy is impossible, with a misleading error, when the backend does not answer

`deployments.rs:1109-1116` (`detected`) turns any failure of `running_config` into `None` with `.ok()`; both
callers swallow again (`gui/deployments.rs:152-153`, `cli.rs:665-669` `if let Ok(detected)`, see UI-6). `complete`
then leaves `smtp = None` (`deployments.rs:849`), and `prepare` fails with `shared.deployments.invalid_smtp_host`
(`:1368-1370`). **Scenario:** the backend is down, the certificate expired or the DNS broken on a multi-user
deployment — exactly when a redeploy is wanted → window and command line say "invalid smtp host", the window has no
way to supply the mail server, the cause (TLS, 5xx, wrong password) is lost. **Fix:** `detected` distinguishes
"labels only" from "the backend did not answer: <cause>"; `prepare` says "mail server unknown, give it".

#### CD-2 (Medium, confirmed): A destroy plan runs through the create registry and its recreate refusal

`plan_prepared` (`deployments.rs:1538`) calls `Prepared::registry` (`:743-746`), which is `spec.registry()` for
every intent, with `.recreatable(server, ssh_key)`; `diff_all` calls `refuse_recreate`. The volume provisioner marks
a smaller-than-declared volume as a recreate (`cloud/hetzner/volume/provisioner.rs:79-84`), the primary ips type and
location likewise. **Scenario:** `sakctl destroy` of a deployment whose volume is smaller than the current default
(an older sakctl, or `data_volume_gb` raised) → `EXIT_RECREATE_REFUSED` "volume would have to be created anew",
although a destroy creates nothing. The apply uses `destroy_registry` (`:1658-1661`), so plan and apply disagree on
which resources are diffed. The destroy spec also omits the user_mode, acme and version labels and uses the dns_mode
default (`:1445-1485`), so a destroy plan shows "has_changes" for a domain-mode deployment. See CD-24.

#### CD-3 (Medium): The version label of the server can stay stale for good

`apply` sets `VERSION_LABEL` only after `wait_for_api` succeeds (`deployments.rs:1790-1806`). When the wait times
out (slow DNS or ACME), the playbook has run and the markers carry the `sak_version` of this sakctl
(`roles.rs:41`), so the next deploy finds the provisioning up to date, `provisioned` stays false and `apply` returns
at `:1761-1763` without the wait and without the label. **Scenario:** deploy → provisioning ok → wait fails → the
user deploys again → "done" without a check of the api, the label stays the old version until a forced deploy.
**Fix:** set the label right after the provisioning; consider the wait for every deploy.

#### CD-4 (Medium): Plan and apply decide differently whether the playbook runs

In the plan `AnsibleRolesProvisioner::diff` uses `has_pending_change(server | volume)` (`ansible_roles.rs:193-195`).
In the apply the registry clears pending before applying (`cloud/api/registry.rs:315`), and the only replacement is
`server_changed`, set for the server alone (`deployments.rs:1713-1718`). **Scenario:** the volume has an in-place
change (delete protection, `volume/provisioner.rs:86`) → the plan says `will_run`; the apply updates the volume,
reads the markers via ssh, nothing changed → `UpToDate`, playbook skipped. **Fix:** the registry exposes "applied
with a change in this run" (CD-25).

#### CD-5 (Medium, suspected): `backend.env` is read by systemd, not by podman

`ansible/roles/backend/templates/sak-backend.container.j2:14` uses `EnvironmentFile=`, whose parser strips leading
and trailing whitespace, interprets quotes and backslash escapes and treats lines starting with `;` or `#` as
comments. `check_smtp` (`deployments.rs:471-486`) refuses only `\n`/`\r`; `backend_env` writes the password
verbatim (`provision.rs:133`). A mail password that starts with a quote, contains a backslash or has leading spaces
reaches the backend altered; the test at `provision.rs:618` checks the file, not what systemd reads. **To confirm:**
`systemd.exec(5)` EnvironmentFile semantics or a quadlet test; then quote the values in `backend_env`.

#### CD-6 (Medium): An ssh failure while reading the markers is "will run", and its cause is dropped

`ansible_roles.rs:150-162` discards stderr (`&mut |_| {}`) and returns the untranslated "ssh ended with exit code";
`diff` maps any error to `HasChanges` (`:202-208`). **Scenario:** a host-key mismatch (someone re-imaged the server)
or a firewall change → the plan says "will run", the apply starts ansible, whose first task waits up to 600 s
(`ansible/playbooks/server.yml:12`) and fails "unreachable", never naming the reason. **Fix:** keep the stderr in
the `DiffItem`; distinguish "no markers" from "could not read" (also CU-3, CU-20).

#### CD-7 (Low): A cancel in a narrow window leaves ansible running

`cancel_apply` aborts the future and calls `stop_runs()` (`gui/deployments.rs:236-241`), which removes the names in
`RUNS`. A run is registered in `RunDir::create_at` (`provision.rs:404-406`) before the container is spawned
(`:259`); a cancel in that window (also `SessionContainer::start`, `ssh.rs:125`, before `run -d`) removes nothing,
the container starts on the detached blocking thread and ansible runs to completion (up to 45 min) while the page
says "cancelled". **Fix:** `ProvisionManager::provision` checks a stopped flag after the spawn, or `stop_runs`
remembers names to remove once they appear.

#### CD-8 (Low): The usage exit code is lost

`checked_name(..).map_err(|error| error.message)` in `detected`, `check_password`, `ssh_target`,
`public_endpoint`, `running_config` (`deployments.rs:1093, 1131, 1155, 1195, 1228`) turns `EXIT_USAGE` into
`EXIT_FAILED`, because these return `Result<_, String>` while `prepare` returns `WizardError`.

#### CD-9 (Low): Costly work for a destroy

`prepare` computes `admin_password_hash` (argon2id 64 MiB × 3, not derived from the cached master key) for every
intent (`deployments.rs:1512`), plus the host key and the user data, which a destroy never uses.

#### CD-10 (Low): A destroy with leftover records without a hash label says "unverifiable"

`deployments.rs:1416-1425`: records of an old deployment with the name label but no hash → `check_password` says
"unverifiable", although `stale_records` would never remove them (identity filter,
`cloud/hetzner/deployment.rs:302-322`). The user should hear "nothing of the deployment is left".

### Smells

#### CD-11 (Medium): Five copies of "find the token → discover → find by name → not_found → verify_password"

`deployments.rs:1094-1107, 1132-1144, 1156-1168, 1196-1208, 1229-1242`. `detected` discovers twice (`:1101`, and
again via `running_config` at `:1112`: ten list calls); `public_endpoint` and `running_config` duplicate the trust
and address derivation (`:1209-1218` vs `:1243-1253`); `apply` discovers a third time after the provisioning
(`:1766-1774`) although `run.host` holds the address. See CD-23.

#### CD-12 (Low): One playbook failure travels on three channels

The `Err` of `provision`, the `failure: Arc<Mutex<Option<String>>>` (`ansible_roles.rs:276-278`, read at
`deployments.rs:1747`) that exists only to skip the `cloud_failure` wrapping, and the `ProvisionFailed` event.
`failed_task` (`ansible_roles.rs:247-262`) recomputes what `failure_of` (`provision.rs:358-369`) put into the
message. A typed error variant removes the mutex and the second tracker (CD-25).

#### CD-13 (Low): Stringly typed plan vocabulary

`resource_status`, `action`, `working` are `&'static str` (`deployments.rs:187-194`), produced by `action()` with
three booleans (`:886-892`) and `working()`; `kind` is a `String` from a serde round trip in `key()` (`:567-572`),
which yields `""` for a non-string. `TaskOutcome` shows the right pattern (enum + serde rename). Also UI-17.

#### CD-14 (Low): `NewDeployment` is a union of three intents

`:621-657`: "needed for X, not used for Y" on half the fields, `password_repeat` on a destroy, `match intent`
through `prepare`, `unwrap_or_default()` with "prepare checked it" comments in `provision_run` (`:720, 728`). See
CD-24.

#### CD-15 (Low): Functions too long with mixed responsibilities

`prepare` ≈ 225 lines (`:1290-1516`: validation, discovery, dns resolution, key derivation, spec building, record
checks; `location` rebound three times, `HetznerProviderRuntime` built twice at `:1391` and `:1443` because the zone
listing needs a runtime before the location is known); `apply` ≈ 170 lines (`:1641-1808`: destroy branch, channel
plumbing, wait, label); `ProvisionManager::provision` ≈ 170 lines (`provision.rs:185-353`: dir, command, spawn,
pumping, timeout, result mapping). See CD-26.

#### CD-16 (Low): The markers are read up to three times per deploy

Each a container and an ssh session: the plan's `diff_all`, the apply's own `diff_all`
(`cloud/api/registry.rs:313`) and the per-resource `diff` in the apply loop (`:319`). Same root as CA-20.

#### CD-17 (Low): A runtime with a made-up location to reach `state()`

`DeploymentManager::list` and `root_domains` build a `HetznerProviderRuntime` with `Location::nbg1`/`cx23`
(`deployments.rs:1062-1066, 1271-1275`) only to call `state()`/`dns_zones()`. `DeploymentManager` and
`ProvisionManager` are stateless unit structs; `&self` buys a tauri `State` and nothing else.

#### CD-18 (Low): Docs out of sync

`deployments.rs:1120-1124` is two doc comments merged (the first paragraph belongs to `running_config`, which has
none at `:1222`); the `verify_password` doc (`:919-921`) says "without a hash on a resource … against the key" but
`check_password` (`:998-1004`) uses the key only when there is no hash at all; `CreateEvent`/`create()` also serve
deploy and destroy; `WizardError` is a leftover name.

#### CD-19 (Low): `alive()` counts EPERM as dead

`provision.rs:539-547` spawns `/bin/kill -0`; EPERM means the process exists. Harmless only because the following
`remove_dir_all` fails. `libc::kill(pid, 0)` with EPERM → alive is the right check.

#### CD-20 (Low): Untranslated user-visible literals

`events.rs:97`, `ansible_roles.rs:160, 206`.

#### CD-21 (Low): Tests that test more or less than their name

`a_failure_that_stops_the_run_names_its_task` also asserts `events_host` (`provision.rs:610-614`);
`the_markers_are_read_back_and_compared` asserts `read_command()` (`roles.rs:221`);
`a_task_is_shown_without_its_role` (`deployments.rs:2010-2025`) tests `events.rs::task_name` and overlaps
`the_tasks_of_the_playbook_show_on_the_row_of_the_provisioning`;
`the_inputs_of_each_role_are_the_variables_it_reads` matches substrings (`roles.rs:238`), so a variable name that
is a substring of a comment passes; `window_fixtures.rs` hand-maintains the registry order (`:85-100`) and the task
list (`:26-68`), which drift from `DeploymentSpec::registry` and the playbook.

### Structure

#### CD-22: Split `core/deployments.rs`

`deployments/input.rs` (NewDeployment, Smtp, UserMode, AcmeCa, defaults, env vars, check_smtp, is_address,
parse_flag), `deployments/password.rs` (verify/check_password, password_proofs, config_endpoint),
`deployments/plan.rs` (PlanResponse, PlannedResource, enums for status/action/working, applied_key/destroyed_key),
`deployments/events.rs` (CreateEvent, TaskOutcome, task_event merged with `events.rs::AnsibleEvent` and
`provision.rs::failure_of`), `deployments/manager.rs` (prepare, plan, apply, destroy); the tests next to each.
Today the file mixes seven concerns: input types and env defaults (`:337-600`), validation (`:469-518, 953-966`),
password proofs (`:919-1014`), plan vocabulary (`:168-218, 873-917`), event mapping (`:220-320`), the manager
(`:1045-1834`), 800 lines of tests.

#### CD-23: One `find_deployment` and one `Endpoint`

`find_deployment(tokens, name) -> (ProviderType, Arc<HetznerApi>, DiscoveredDeployment)` and
`Endpoint { url, trust, address }` built once; `detected`, `check_password`, `ssh_target`, `public_endpoint`,
`running_config` and the wait in `apply` use them and return `WizardError`. Resolves CD-8, CD-11.

#### CD-24: A typed intent on `Prepared`

`Create { provisioning }`, `Deploy { provisioning }`, `Destroy { delete_data }`; a destroy gets its own
`plan_destroy`/`destroy` pair that builds `destroy_registry` for plan and apply; `ProvisionRun` is built for create
and deploy only. Resolves CD-2, CD-9, CD-14.

#### CD-25: sak-cloud tells the provisioners what was applied with a change

The registry exposes "applied with a change in this run" to the diffs (removes `server_changed`,
`ansible_roles.rs:98-100`, `deployments.rs:1690`; CD-4), caches the plan's diffs for the apply (CD-16, CA-20), and
gets an `Error::Provisioning { task, message }` (removes the `failure` mutex and the duplicate task tracking,
CD-12).

#### CD-26: Break `ProvisionManager::provision` apart

`RunFiles::write(run)`, `container_command(runtime, dir, events)`, `pump_output(child, receiver, timeout)`,
`outcome(status, failed, timed_out)`; the "cancelled before the spawn" check goes there (CD-7).

---

## UI: command line, window commands, pages, locales

### Bugs

#### ~~UI-1 (High, confirmed): The deploy of the page can apply another deployment than the one shown~~ **fixed**

`gui/deployments.rs:132-157` stores the *last check that finished* with no link to the page; `apply_checked`
(`:170-182`) takes whatever is there. `ui/deployment.js:80-90` guards its own view with `current !== shown`, but the
Rust state is uncorrelated. **Scenario:** open A (its check is slow: ssh reads the markers) → Back → open B → B's
check finishes first → A's check finishes and overwrites the state with A → "Deploy changes" on B's page runs
`apply_checked` → A is deployed with A's password. **Fix:** `check_deployment` returns an id, `apply_checked(id,
force)` refuses a mismatch; and the state is cleared when the page leaves.

#### UI-2 (Medium, confirmed): A destroy runs after the user cancelled the form

`ui/create.js:375-384`: for a destroy the submit does not `setBusy`, so Cancel stays enabled while `runDestroy`
awaits `plan_deployment` (`ui/run.js:196-199`). **Scenario:** Destroy → confirm → (plan pending) → Cancel → the
list shows → the plan resolves → `run()` switches to the run view and starts the destroy. A create is safe only
because `setBusy(true)` disables the cancel button (`create.js:386, 261-264`). **Fix:** a generation guard in
`run()` (UI-25) and the busy state for the destroy.

#### UI-3 (Medium, confirmed): "Also delete the data volume" stays ticked for the next destroy

`ui/create.js:329-365` (`showCreate`) never resets `deleteDataInput.checked`; only `resetForm` (`:408-424`,
`form.reset()`) does, on Cancel only. After a destroy ran (`run(plan, deployment, showList)`, `run.js:198`) nothing
resets the form. **Scenario:** destroy A with its data → Done → open B → Destroy → the form is red with the box
ticked. Against "Cancel is the safe default" (UI-UX). Same cause: the name and the smtp fields, the smtp password
included, of the previous create stay in the form (`:319, 152-159`).

#### UI-4 (Medium, confirmed): A deployment without a located resource cannot be checked in the window

`gui/deployments.rs:132` takes `location: Location`, but `DeploymentResponse.location` is `Option<Location>`
(`core/deployments.rs:40`; `None` without server, volume or primary ip). `ui/deployment.js:80` passes
`shown.location` as is → tauri rejects the call with a raw "invalid args `location` … invalid type: null" on the
page. Hits a half-destroyed or half-created deployment, the one the user wants to check. The command line has a
default (`cli.rs:264`); the window needs `Option<Location>` and the same fallback.

#### UI-5 (Medium): The window shows command-line instructions it cannot act on

`gui/deployments.rs:154` calls `complete(deployment, &detected, "")`; its errors are `shared.` texts with cli
wording: `smtp_password_needed` "give it again with SAK_SMTP_PASSWORD" (`en.yml:106`), and via `prepare`
`no_user_mode` "(--user-mode)" (`:109`), `invalid_smtp_host` "(--smtp-host)" (`:110`), `invalid_admin_email`
"(--admin-email)" (`:113`), `deploy_without_root_domain` "--root-domain" (`:104`), `config.no_root_domain` (`:139`).
**Scenario:** a multi-user deployment whose backend does not answer the smtp password (CD-1) → the page says to
set an environment variable. Either `ui.`/`cli.` variants, or the window gets the fields to supply what is missing.

#### UI-6 (Medium): `sakctl deploy` swallows the detection failure and fails later with the wrong cause

`cli.rs:664-670`: `if incomplete && let Ok(detected) = …` ignores an `Err` (api unreachable, wrong password, no
provider) and continues; `prepare` then fails with `no_user_mode` or `invalid_admin_email`. The window propagates it
(`gui/deployments.rs:150`). The same root as CD-1; see UI-12.

#### UI-7 (Low): `sakctl ssh -- <cmd>` exit codes collide with the documented table

`cli.rs:766-772` makes the remote command's exit code sakctl's own: `-- exit 3` reads as "domain taken", `2` as
"arguments wrong". `EXIT_CODES` (`:32-43`) does not mention it. Document it, or reserve a range.

#### UI-8 (Low, suspected): `RunningApply` can lose the running handle when two runs overlap

`gui/deployments.rs:97-114`: a second `run` overwrites the handle, and whichever ends first sets `None`, so cancel
and `restart`'s `is_running` (`gui/update.rs:68`) see nothing while the other still runs. Through the window only in
a narrow window: `run-cancel` fires `cancel_apply` without awaiting (`ui/run.js:225-228`), the aborted future sets
`None` on its next poll; a maintenance started in between is then unregistered. **Fix:** compare the handle (or a
generation id) before clearing. Related CU-2.

#### UI-9 (Low): The password of an opened deployment is never dropped on the page

`ui/password.js:35-44`: `opened` is set and only overwritten. `deployment-back` (`ui/deployment.js:156-160`) clears
`current` but not `opened`; the list and the providers page keep it in module state. `showCreate` of a new
deployment could read it (`create.js:311` guards with `existing`, so no leak today).

#### UI-10 (Low): Focus and keyboard on the maintenance menu

`ui/deployment.js:130-141`: after a choice the view switches while the focus is on the now-hidden `menuitem`, so it
falls to `body`; `role="menu"` has no arrow-key handling (only Escape, `:143-148`). `showCreate` for a destroy
focuses the red Destroy button (`create.js:364`: name and password are filled), so Enter leads straight to the
confirmation.

### Smells

#### UI-11 (Medium): The whole maintenance flow is written twice

`cli.rs:777-870` and `gui/maintenance.rs:55-123` are the same `match` (limited status/update/reboot → lines →
reboot hint → `wait_after_reboot` → back line), differing in the hint key and the sink. Belongs in core:
`maintenance::perform(action, manager, tokens, runtime, name, password, on_line) -> Result<Vec<Line>>`; the
command line logs, the window sends.

#### UI-12 (Medium): The detect-and-complete of a deploy is written twice

`cli.rs:659-671` and `gui/deployments.rs:135-154` both build the `NewDeployment`, call `detected`, then `complete`.
A `DeploymentManager::completed(new, smtp_password)` in core removes both, and UI-6 with them.

#### UI-13 (Low): The startup checks are run twice

`cli.rs:541-584` and `gui/checks.rs:13-36` both build the checks, run them on a blocking thread, set the runtime
and the tokens. Core could return a `Found` plus a `ContainerRuntime` from one `checks::run_all(tokens, on_event)`.

#### UI-14 (Low): `execute` matches the same enum four times

`cli.rs:586-688`: password, smtp password, provider and dispatch each re-match `Command`; `Update` is
`unreachable!` twice (`:626, 631`). A `Command::common(&self)` accessor, or `Update` out of the enum, removes both.

#### UI-15 (Low): `ssh_target` + runtime lookup repeated four times

`cli.rs:746-751, 787-792, 902-908, 950-956`. The name trimming differs: `ssh` passes the untrimmed name to
`ssh_target` (`:747`) but trims it for the message (`:756`); backup, restore and maintenance trim first. One
`session(...) -> (SshTarget, runtime)` helper.

#### UI-16 (Low): Boolean parameters

`password(ask, twice, from_env)` (`cli.rs:417`), `deployment(intent, delete_data, …)` (`:474`), `deploy(…,
show_log)` (`:1071`), `quit(failed)` (`gui/checks.rs:46`), `apply_checked(force)`; in JS `setBusy`, `setStatus(msg,
failed)`, `showTyped`. The two-bool `password` reads worst at its call sites (`:592-606`).

#### UI-17 (Low): Stringly typed plan and events across the boundary, the texts of an apply mapped twice

`cli.rs:1117` compares `resource.action == Some("will_destroy")`; `PlannedResource.working/resource_status/action`
are `&'static str` keys (CD-13). `ui/run.js:22-27` `APPLIED` re-implements `applied_key`
(`core/deployments.rs:203`), `run.js:124` re-implements `destroyed_key`. Let `CreateEvent::Applied/Destroyed` carry
the text key once.

#### UI-18 (Low): Six copies of `setStatus`, five hand-rolled `%{…}` substitutions

`providers.js:73`, `deployments.js:89`, `password.js:46`, `deployment.js:30`, `create.js:254`, `run.js:45`,
`maintenance.js:26`; `replaceAll('%{name}')` in `deployment.js:122`, `create.js:52, 176`, `run.js:133`,
`deployment.js:91`, plus `fill()` in `update.js:16`. One `text(key, values)` and one `status(element, message,
failed)` in `views.js`.

#### UI-19 (Low): The "has changes" rule and its button differ between pages

`deployment.js:86-88` disables Deploy when every resource is `up_to_date`; `run.js:92` hides it. One rule, two
behaviours.

#### UI-20 (Low): `MaintenanceRequest` derives `Debug` with its `password`

`gui/maintenance.rs:26-31`. Nothing prints it today, but `HetznerToken` (`cli.rs:250`) and `DeploymentDefaults`
(`core/deployments.rs:338`) deliberately have no `Debug` (S10 of the first review).

#### UI-21 (Low): Locale inconsistencies

- `shared.deployments.weak_password`: en has `%{hint}` (`en.yml:90`), de drops it (`de.yml:90`); German users never
  see the strength hint.
- `shared.plan.intro_deploy` de ends with ":" (`de.yml:151`), en and the other intros do not; the page renders
  "…aus: alpha".
- de has sentences en lacks: `container_runtime.needed`, `image.wrong_answer`, `provider_tokens.invalid`
  (`de.yml:49, 60, 66`).
- `shared.provision.no_volume` (`en.yml:85`) is defined and used nowhere.
- `ui_texts` (`gui/i18n.rs:14-24`) ships every `shared.` text to the page, the cli-worded ones of UI-5 included.

#### UI-22 (Low): Stale docs and names

`cli.rs:736-737` "prints the running configuration…" sits on `ssh`; `deploy()` (`:1071`) also runs destroys.

#### UI-23 (Low): An unknown first word opens the window

`main.rs:19`: `sakctl lst` silently starts the window instead of a usage error; `is_command_line` (`cli.rs:352`)
duplicates the subcommand list clap knows.

### Structure

#### UI-24: Split `cli.rs`

1724 lines, three responsibilities: `cli/mod.rs` (Cli, Command, `is_command_line`, `run`, `execute`);
`cli/args.rs` (Provider, DeploymentArgs, MaintenanceArgs, the `parse_*`); `cli/secrets.rs` (`hcloud_token`,
`password`, `smtp_password`, `confirm_restore`); `cli/log.rs` (Level, `log`, `log_line`, `print_json`, `size`);
`cli/deploy.rs` (`list`, `deploy`); `cli/server.rs` (`ssh`, `backup`, `restore`, `maintenance`, `show_config`).
The tests move with their functions. With UI-14, UI-15.

#### UI-25: A small router in `views.js`, no dynamic `import()`

The cycles `deployments.js → create.js → run.js ⇢ deployments.js/deployment.js`, `deployment.js → maintenance.js ⇢
deployment.js`, `password.js ⇢ deployment.js/deployments.js` are unnecessary: every export is a hoisted `function`
used only inside handlers, so static imports in a cycle work in ES modules. A router — `register('deployments',
showDeployments)`, `navigate('deployments', data)` — hands each view a generation token (`const gen =
enter('deployment')` … `if (!isCurrent(gen)) return`) that replaces the hand-written `current !== shown` /
`running === current` guards (`deployment.js:81, 93, 98`, `run.js:164, 171`, `maintenance.js:50, 56, 61, 65`,
`password.js:61`) and fixes UI-2. `text(key, values)` and `status()` live there too (UI-18).

#### UI-26: `gui/` holds state and channels only

`check_deployment`'s detect + complete + plan (`gui/deployments.rs:135-155`) and the maintenance flow (UI-11) are
domain logic and belong in core. `RunningApply` and `CheckedDeployment` are used by three modules and belong in
`gui/state.rs`; `RunningApply` is misnamed since the maintenance uses it too.

---

## Areas examined with no finding worth the name

- `keys.rs`: the derivation chain (argon2id → hkdf per purpose), the nfc normalisation and the password strength
  check are consistent; no key reaches an argument or a file (CU-7 is the one caveat).
- `update.rs`: downgrades are refused (`status()` requires strictly newer), the temp file is written fully before
  `self_replace`, which renames atomically; a symlinked install path resolves through `current_exe()`.
- The contract between the rust side and `sak-backend.j2`: exit codes 0/1/2/127 and the three json shapes match
  the types (`from`, `time`, `at` optional, `kernel.installed` always present); apt output goes to stderr so stdout
  stays json. The two mismatches are CU-3 and CU-4.
- `config.rs`: the signed request never carries the password; the errors carry no signature.
