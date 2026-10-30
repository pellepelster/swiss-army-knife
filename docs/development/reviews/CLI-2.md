---
title: "Review: sak-cli, second pass"
linkTitle: "sak-cli (2)"
description: "The second review of sak-cli: structure, simplifications, smells, bugs."
weight: 2
---

# Review: sak-cli, second pass

**Date:** 2026-10-30
**Scope:** `sak-cli/src-tauri` (Rust: core, cli, gui), `sak-cli/ui` (JS, HTML, CSS), `sak-cli/cloud` (sak-cloud).
**Focus:** code structure, simplifications, code smells, bugs. The findings of the first review (2026-10-29,
security and correctness) that are still open are merged in under `R1-`; each names its old ID. The rest of the
first review is fixed, and its file is gone.
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
| `R1-`  | open findings of the first review, checked again against the code on 2026-10-30              |

Each finding has a severity: **High** (wrong result or data at risk in a realistic scenario), **Medium** (a bug on a
less common path, or a smell that hides bugs), **Low** (a smell or a simplification). Findings marked *(confirmed)*
were checked a second time against the code after the slice reviewer reported them; the others rest on the slice
reviewer's reading. A finding that is fixed is removed; one that an open finding still refers to stays,
~~struck through~~ and marked **fixed**. The commit that fixes a finding names its ID.

## Summary

The review found 118 findings: 5 High, 28 Medium, 65 Low, and 20 proposals of structure. The table counts them as
the review found them; the sections below hold the ones still open. The first review adds 7 open findings
(`R1-`: 2 Medium, 5 Low).

| Slice                          | High | Medium | Low | Structure |
|--------------------------------|-----:|-------:|----:|----------:|
| CA sak-cloud api and registry  |    1 |      5 |  11 |         4 |
| CP sak-cloud provisioners      |    1 |      5 |  12 |         5 |
| CU core utilities              |    1 |      5 |  13 |         3 |
| CD core deployments            |    1 |      6 |  14 |         5 |
| UI command line, window, pages |    1 |      7 |  15 |         3 |
| **Total**                      | **5** | **28** | **65** |    **20** |

### Fix first

1. **UI-2** a destroy runs after Cancel.
2. **UI-4** a deployment without a location cannot be checked in the window.
3. **UI-5** the window shows command-line instructions it cannot act on.

### The structural threads that run through all slices

- **One thing written more than once.** *Done: the maintenance flow, the deploy completion, the checks, the
  lookup of a deployment, the driver of `sak-backend`, the request plumbing of the api (one `ResourceApi<T>`; the
  dns zones keep theirs), the provisioner skeleton (`HetznerKind`; the server and the dns record keep theirs).*
- **Plan and apply disagree.** The registry refuses duplicates and owner-less recreates only in the apply (CA-2,
  CA-3), the apply diffs again instead of applying the plan shown (R1-3), the playbook decision differs (CD-4), a
  pending label
  change reads as a recreate (CP-3). Making the plan the one gate and letting the registry tell what it applied
  resolves all of them (CD-25, CP-22).
- **Errors that lose their cause.** *Done: the running configuration, the exit codes of ssh and of the lock, a
  deployment that cannot be compared, the reading of the markers, a value of Hetzner this sakctl does not know.*
- **Booleans and strings standing in for enums.** The intents on `NewDeployment` (CD-14), the strings and flags
  of the deployment spec (CP-17), a few flags of the command line (UI-16).
  *Done: `DiffItem`, the plan words and kinds, the reasons of a reboot, the password prompt, the transport.*
- **Files that have outgrown themselves.** *Done: `cli.rs`, `core/deployments.rs`,
  `cloud/hetzner/deployment.rs`.*

---

## CA: sak-cloud, api and registry

### Bugs

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

#### CA-13 (Low): `ProviderManager`, `ProviderConfiguration` and `validate` serve only the example

sakctl constructs `HetznerProviderRuntime::from_api` everywhere (`src-tauri/src/core/deployments.rs:1062, 1271,
1392, 1444`) and checks tokens itself. `validate` (`providers/hetzner.rs:116-129`) lists every page of ssh keys to
test a token and wraps the `ApiError` into `Error::Message`, losing the code. `#[async_trait]` on `ProviderRuntime`
(`providers/mod.rs:20`) has no async method.

#### CA-15 (Low): `Listable` is implemented six times for one test

`api/provisioner.rs:17`, used by `tests/hetzner_dns.rs` only. It lists the whole project without a label filter.

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

#### CU-2 (Medium, confirmed): A maintenance timeout stops every tracked run, not its own session

`maintenance.rs:115` calls `provision::stop_runs()`, which drains the global `RUNS` and removes all containers and
run directories (`provision.rs:444-455`). The window cannot start a deploy and a maintenance at the same time today
(the run view has no way back while it runs), so this is a latent flaw: `limited` should stop its own
`SessionContainer`, which needs a handle from `run_streaming`. The same `stop_runs` from an async context runs
`docker rm --force` synchronously on a tokio worker (`provision.rs:472-478`).

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

### Smells

#### CU-16 (Low): `BackupManager` is a unit struct with `&self` methods

`backup.rs:32, 139, 187`, called as `BackupManager.backup(...)`; the receiver carries nothing. The doc line at
`backup.rs:16-17` is duplicated.

#### CU-18 (Low): `block_on` hides in `container.rs`

`container.rs:116-125`, used by `checks/provider_tokens.rs:2`; it panics when called from an async task and relies
on a comment. `pull()` discards the bollard error silently (`container.rs:130`), so the user never learns why the
progress disappeared.

### Structure

#### CU-21: A cancellable ssh session per call

`run_streaming` returns or takes a handle that removes its own `SessionContainer`; `limited` stops that handle
instead of `stop_runs()`. With `ServerAlive*`/`BatchMode` in `ssh_arguments` and a `limited` around backup and
restore this resolves CU-2.

#### CU-22: Split `container.rs`

- Split `container.rs`: runtime detection, canary and `command()` stay; `PullProgress` and the bollard pull go to
  `container/pull.rs`; `block_on` to a small `core::runtime`.

---

## CP: sak-cloud, deployment and provisioners

### Bugs

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

#### CP-9 (Low): Two power cycles when both ips need assigning

`server/provisioner.rs:343-351` calls `assign` once per ip; each powers off and on (`:132-146`) with the stale
`server.status` from before the first cycle.

#### CP-10 (Low): Duplicates are handled differently per kind

`deployment.rs:413-446`: a second server, key or firewall with the name label is dropped with a warning;
`:448-457` every volume and ip is collected; `declared_by:835` takes `volumes.first()` as the data volume, so a
second labelled volume listed first sets the declared size. Only the ssh key knows `Duplicate`
(`ssh_key/provisioner.rs:90-109`); with the server present and its key renamed, the server diff fails with "ssh key
not found" (`server/provisioner.rs:63-69`) instead of the duplicate message.

#### CP-11 (Low): A firewall selector the parser does not know fails the whole plan

`hetzner/firewall/runtime.rs:19-25` splits on `,` and `=`; a valid selector with `!=`, `in (…)` or a bare key makes
`find` fail with "invalid label selector", and with it the diff, state and apply of the deployment. Several
selectors merge into one map (`a=1` + `a=2` collapse).

### Smells

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

### Structure

#### CP-22: Pending carries whether the dependency's value changes

A fingerprint recreate: yes; a label change: no (CP-3). The volume size recreate becomes an in-place resize (CP-6).

#### CP-23: Decide label ownership once

Either ignore and keep keys outside `sak.pelle.io/` in `hetzner_label_diff` and in every update (CP-5), or change
the ARCHITECTURE sentence.

---

## CD: core deployments and provisioning

### Bugs

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

#### CD-7 (Low): A cancel in a narrow window leaves ansible running

`cancel_apply` aborts the future and calls `stop_runs()` (`gui/deployments.rs:236-241`), which removes the names in
`RUNS`. A run is registered in `RunDir::create_at` (`provision.rs:404-406`) before the container is spawned
(`:259`); a cancel in that window (also `SessionContainer::start`, `ssh.rs:125`, before `run -d`) removes nothing,
the container starts on the detached blocking thread and ansible runs to completion (up to 45 min) while the page
says "cancelled". **Fix:** `ProvisionManager::provision` checks a stopped flag after the spawn, or `stop_runs`
remembers names to remove once they appear.

### Smells

#### CD-12 (Low): One playbook failure travels on three channels

The `Err` of `provision`, the `failure: Arc<Mutex<Option<String>>>` (`ansible_roles.rs:276-278`, read at
`deployments.rs:1747`) that exists only to skip the `cloud_failure` wrapping, and the `ProvisionFailed` event.
`failed_task` (`ansible_roles.rs:247-262`) recomputes what `failure_of` (`provision.rs:358-369`) put into the
message. A typed error variant removes the mutex and the second tracker (CD-25).

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

### Structure

#### CD-23: The wait after the provisioning discovers the deployment again

`DeploymentManager::find` and `Endpoint` serve every lookup of a deployment but one: the wait in `apply` still
discovers the deployment after the provisioning, although `run.host` holds the address.

#### CD-24: A typed intent on `Prepared`

`Create { provisioning }`, `Deploy { provisioning }`, `Destroy { delete_data }`; a destroy gets its own
`plan_destroy`/`destroy` pair that builds `destroy_registry` for plan and apply; `ProvisionRun` is built for create
and deploy only. Resolves CD-14.

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

#### UI-2 (Medium, confirmed): A destroy runs after the user cancelled the form

`ui/create.js:375-384`: for a destroy the submit does not `setBusy`, so Cancel stays enabled while `runDestroy`
awaits `plan_deployment` (`ui/run.js:196-199`). **Scenario:** Destroy → confirm → (plan pending) → Cancel → the
list shows → the plan resolves → `run()` switches to the run view and starts the destroy. A create is safe only
because `setBusy(true)` disables the cancel button (`create.js:386, 261-264`). **Fix:** a generation guard in
`run()` (UI-25) and the busy state for the destroy.

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
**Scenario:** a multi-user deployment whose backend does not answer the smtp password → the page says to
set an environment variable. Either `ui.`/`cli.` variants, or the window gets the fields to supply what is missing.

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

#### UI-16 (Low): Boolean parameters — *partly fixed*

`password(ask, twice, from_env)` (`cli.rs:417`), `deployment(intent, delete_data, …)` (`:474`), `deploy(…,
show_log)` (`:1071`), `quit(failed)` (`gui/checks.rs:46`), `apply_checked(force)`; in JS `setBusy`, `setStatus(msg,
failed)`, `showTyped`. The two-bool `password` reads worst at its call sites (`:592-606`). *`password` takes `Typed::Once`/`Typed::Twice`; the others carry a
named flag of the command line or the page through and stay bools.*

### Structure

#### UI-25: A small router in `views.js`, no dynamic `import()`

The cycles `deployments.js → create.js → run.js ⇢ deployments.js/deployment.js`, `deployment.js → maintenance.js ⇢
deployment.js`, `password.js ⇢ deployment.js/deployments.js` are unnecessary: every export is a hoisted `function`
used only inside handlers, so static imports in a cycle work in ES modules. A router — `register('deployments',
showDeployments)`, `navigate('deployments', data)` — hands each view a generation token (`const gen =
enter('deployment')` … `if (!isCurrent(gen)) return`) that replaces the hand-written `current !== shown` /
`running === current` guards (`deployment.js:81, 93, 98`, `run.js:164, 171`, `maintenance.js:50, 56, 61, 65`,
`password.js:61`) and fixes UI-2.

---

## R1: open from the first review

The first review (2026-10-29) had 31 findings. Checked again against the code on 2026-10-30, 24 are fixed: the
ownership checks of every provisioner (S1, S2), secrets never as arguments (S3), the strong key derivation with its
password check (S4), the run directory and the signal handling (S5), the accepted risk of the host key (S7), the
label of the token check (S8), `open_url` (S9), the structs with secrets (S10, moot since S3), the rule about the
addresses of the user's servers (S11), the recreate set and the location of a deploy (C1), the settings a deploy
reads back (C2), foreign records of any type (C3), "unverifiable" when nothing proves a password (C4), the retries
and the time limit of ansible (C5), the text of a removed record (C7), the trimmed token (C8), the mail server (C9),
every resource in the state (C11), and the smells M1, M2, M3, M6, M7. These are left:

### Bugs and security

#### R1-1 (Medium): The images of the tools are pinned to a tag only (was S6)

`core/container.rs:20-28` runs `ghcr.io/pellepelster/sak-cli-tools:<version>` (and the canary of the same tag), and
the server pulls the backend images by tag (`sak_image_tag`). A tag on ghcr.io can be replaced; the image of the
tools gets the private client key of every server sakctl provisions. **Fix:** verify a signature (cosign) before a
run, or pin each release to digests. GitHub issue #91 tracks it.

#### R1-3 (Medium): The command line applies a plan that removes or recreates without asking (was C6)

`cli/deploy.rs` prints the plan and applies it at once; a `sakctl deploy` that would create the server anew or
remove records goes ahead with no confirmation and there is no `--yes`. In the window the apply of a check diffs the
project again (the registry diffs before it applies, `cloud/src/api/registry.rs:313`), so a project that changed
between the check and the click is applied as it is then, not as the page showed it. **Fix:** on a terminal, ask
before a plan that destroys or recreates, with `--yes` for scripts; let the apply stop when its diff differs from
the plan it was given (CA-20 keeps the plan's diffs for the apply).

#### R1-4 (Low): The plan does not show a second server, ssh key or firewall of a deployment (was C10)

`discover` keeps them (`DiscoveredDeployment::left_out`), the list shows them as duplicates and the password is
checked against their hashes too. The plan of a deploy or destroy still shows only the resources of the spec: a
duplicate stays out of sight there, and a destroy leaves it. **Fix:** show the left-out resources in the plan as
duplicates; with CP-10, handle duplicates the same way for every kind.

#### R1-5 (Low): Two runs on the same deployment are not prevented (was C12)

Nothing stops two sakctl (two machines, or the command line and the window) from deploying or destroying the same
deployment at once; the two applies can destroy and create the server at the same time. `sak-backend` locks only
the backup, restore and maintenance on the server. **Fix:** a lock label on the ssh key of the deployment for the
time of an apply, refused when another value is there; or record it as an accepted risk.

---

## Areas examined with no finding worth the name

- `keys.rs`: the derivation chain (argon2id → hkdf per purpose), the nfc normalisation and the password strength
  check are consistent; no key reaches an argument or a file (CU-7 is the one caveat).
- `update.rs`: downgrades are refused (`status()` requires strictly newer), the temp file is written fully before
  `self_replace`, which renames atomically; a symlinked install path resolves through `current_exe()`.
- The contract between the rust side and `sak-backend.j2`: exit codes 0/1/2/127 and the three json shapes match
  the types (`from`, `time`, `at` optional, `kernel.installed` always present); apt output goes to stderr so stdout
  stays json. The two mismatches it found (the exit codes of ssh and of the lock) are fixed.
- `config.rs`: the signed request never carries the password; the errors carry no signature.
