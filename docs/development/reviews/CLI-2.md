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
the review found them; the sections below hold the ones still open.

| Slice                          | High | Medium | Low | Structure |
|--------------------------------|-----:|-------:|----:|----------:|
| CA sak-cloud api and registry  |    1 |      5 |  11 |         4 |
| CP sak-cloud provisioners      |    1 |      5 |  12 |         5 |
| CU core utilities              |    1 |      5 |  13 |         3 |
| CD core deployments            |    1 |      6 |  14 |         5 |
| UI command line, window, pages |    1 |      7 |  15 |         3 |
| **Total**                      | **5** | **28** | **65** |    **20** |

### Fix first

Nothing of the list is left.

### The structural threads that run through all slices

- **One thing written more than once.** *Done: the maintenance flow, the deploy completion, the checks, the
  lookup of a deployment, the driver of `sak-backend`, the request plumbing of the api (one `ResourceApi<T>`; the
  dns zones keep theirs), the provisioner skeleton (`HetznerKind`; the server and the dns record keep theirs).*
- **Plan and apply disagree.** The playbook decision differs (CD-4), a pending label change reads as a recreate
  (CP-3). Letting the registry tell what it applied resolves both (CD-25, CP-22). *Done: the plan is the one gate
  of the apply, and the window applies only what its check showed.*
- **Errors that lose their cause.** *Done: the running configuration, the exit codes of ssh and of the lock, a
  deployment that cannot be compared, the reading of the markers, a value of Hetzner this sakctl does not know.*
- **Booleans and strings standing in for enums.** *Done: `DiffItem`, the plan words and kinds, the reasons of a
  reboot, the password prompt, the transport, the intent of a prepared deployment, the flags of the command line
  and of the status lines of the pages, the settings of the deployment spec.*
- **Files that have outgrown themselves.** *Done: `cli.rs`, `core/deployments.rs`,
  `cloud/hetzner/deployment.rs`.*

---

## CA: sak-cloud, api and registry

### Bugs

#### CA-6 (Low): A parent that is itself `ParentMissing` or `Duplicate` counts as present

`registry.rs:267-271` marks a child `ParentMissing` only when a dependency's diff is `Missing`. **Scenario:** the
ssh key is missing → the server is `ParentMissing` → the roles (which depend on the server) are diffed anyway, and
their lookups of the server return `None`. Each provisioner guards this itself today
(`dns_record/provisioner.rs:107`, `src-tauri/src/core/ansible_roles.rs:197`), which makes the registry's check
half-redundant. **Fix:** include `ParentMissing | Duplicate` at `:270`, or make it the provisioner's job alone.

### Structure

#### CA-21: `pending` as a per-run value, `diff_all` takes `&self`

`diff_all` needs `&mut self` only for `pending`. A `DiffRun<'a> { providers: &'a LookupProviders, pending }` that
implements `DiffContext` makes `diff_all` `&self`, and a plan cannot leak pending state into the next run.

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

### Structure

#### CU-21: A cancellable ssh session per call

`run_streaming` returns or takes a handle that removes its own `SessionContainer`; `limited` stops that handle
instead of `stop_runs()`. With `ServerAlive*`/`BatchMode` in `ssh_arguments` and a `limited` around backup and
restore this resolves CU-2.

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
are left alone. **Scenario:** `env=prod` added in the console → state "Changed", the deploy removes it. The decision
is GitHub issue #131.

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

### Structure

#### CP-22: Pending carries whether the dependency's value changes

A fingerprint recreate: yes; a label change: no (CP-3). The volume size recreate becomes an in-place resize (CP-6).

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

### Structure

#### CD-23: The wait after the provisioning discovers the deployment again

`DeploymentManager::find` and `Endpoint` serve every lookup of a deployment but one: the wait in `apply` still
discovers the deployment after the provisioning, although `run.host` holds the address.

#### CD-25: sak-cloud tells the provisioners what was applied with a change

The registry exposes "applied with a change in this run" to the diffs (removes `server_changed` of
`core/ansible_roles.rs` and its setting in the apply; CD-4).

---

## UI: command line, window commands, pages, locales

### Bugs

#### UI-8 (Low, suspected): `RunningApply` can lose the running handle when two runs overlap

`gui/deployments.rs:97-114`: a second `run` overwrites the handle, and whichever ends first sets `None`, so cancel
and `restart`'s `is_running` (`gui/update.rs:68`) see nothing while the other still runs. Through the window only in
a narrow window: `run-cancel` fires `cancel_apply` without awaiting (`ui/run.js:225-228`), the aborted future sets
`None` on its next poll; a maintenance started in between is then unregistered. **Fix:** compare the handle (or a
generation id) before clearing. Related CU-2.

---

## R1: open from the first review

### Bugs and security

---

## Areas examined with no finding worth the name

- `keys.rs`: the derivation chain (argon2id → hkdf per purpose), the nfc normalisation and the password strength
  check are consistent; no key reaches an argument or a file (the key passing the container engine is an accepted
  risk of ARCHITECTURE.md).
- `update.rs`: downgrades are refused (`status()` requires strictly newer), the temp file is written fully before
  `self_replace`, which renames atomically; a symlinked install path resolves through `current_exe()`.
- The contract between the rust side and `sak-backend.j2`: exit codes 0/1/2/127 and the three json shapes match
  the types (`from`, `time`, `at` optional, `kernel.installed` always present); apt output goes to stderr so stdout
  stays json. The two mismatches it found (the exit codes of ssh and of the lock) are fixed.
- `config.rs`: the signed request never carries the password; the errors carry no signature.
