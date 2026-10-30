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
- **Plan and apply disagree.** *Done: the plan is the one gate of the apply, the window applies only what its
  check showed, the registry tells the provisioners what it applied with a change, so plan and apply run the
  playbook alike, and a change of labels alone is not pending for the dependents.*
- **Errors that lose their cause.** *Done: the running configuration, the exit codes of ssh and of the lock, a
  deployment that cannot be compared, the reading of the markers, a value of Hetzner this sakctl does not know.*
- **Booleans and strings standing in for enums.** *Done: `DiffItem`, the plan words and kinds, the reasons of a
  reboot, the password prompt, the transport, the intent of a prepared deployment, the flags of the command line
  and of the status lines of the pages, the settings of the deployment spec.*
- **Files that have outgrown themselves.** *Done: `cli.rs`, `core/deployments.rs`,
  `cloud/hetzner/deployment.rs`.*

---

## CA: sak-cloud, api and registry

---

## CU: core utilities

### Bugs and security

---

## CP: sak-cloud, deployment and provisioners

### Bugs

#### CP-2 (Medium): A server apply can leave the server powered off; an ip it already has is not unassigned

`hetzner/server/provisioner.rs:118-148` `assign` powers the server off (`:134`), unassigns the *declared* ip from
wherever it is (`:137`), but never the ip the server has now (`runtime.ipv4_id`, which the diff at `:251-259` saw
differs). Suspected: Hetzner refuses a second ipv4/ipv6 on a server. On any error after `power_off` nothing powers
the server on again; the `power_on` at `:357` is reached on success only. **Scenario:** a server created without
the primary ips (an older sakctl, or by hand) → the deploy ends with an api error and the server off.

#### CP-4 (Medium): A server whose image Hetzner deleted is recreated by the next deploy without a decision

`hetzner/server/runtime.rs:40` maps a null image (`api/servers.rs:49`) to `"unknown"`; `server/provisioner.rs:230`
treats `resource.image != runtime.image` as a recreate; the server is recreatable (`deployment.rs:1040`). Same
mechanism with the type: sakctl declares `server_type_for(location)` (`src-tauri/src/core/deployments.rs:558-563`),
not the existing type, so a server upgraded in the console is silently recreated with the smaller type. The data is
on the volume, but a recreate of a running server should not follow from "unknown": compare only when the image is
known, or make the recreate a confirmed step.

---

## CD: core deployments and provisioning

---

## UI: command line, window commands, pages, locales

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
