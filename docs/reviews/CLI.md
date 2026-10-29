# Review: sak-cli (sakctl)

**Date:** 2026-10-29
**Scope:** `sak-cli/src-tauri` (Rust, Tauri and clap), `sak-cli/ui` (JS, HTML, CSS), `sak-cli/cloud` (sak-cloud),
`sak-cli/Dockerfile`. The rules are `AGENT.md`, `docs/UI-UX.md`, `GLOSSARY.md` and `docs/fdr/FDR-001-dns-mode.md`.
**Not in scope:** The admin API token and the `config show` command. Another change adds them to `cli.rs`,
`core/deployments.rs` and `core/keys.rs` at this time. This review does not examine that code.

This review is read-only. Each finding has a file and line reference. Each finding also has a failure scenario or a
recommended change. The line numbers refer to commit `5a893f2`. At that commit, `sak-cli` had no changes that were
not committed. The other change also edits `provision.rs`, `core/mod.rs` and the locales, so the line numbers in
those files can move.

## Summary

| Section             | High | Medium | Low |
|---------------------|-----:|-------:|----:|
| 1. Security         |    1 |      5 |   6 |
| 2. Correctness      |    1 |      5 |   6 |
| 3. Code smells      |    0 |      1 |   6 |
| **Total**           |  **2** | **11** | **18** |

Section 4 lists the tests that are missing for these findings.

---

## 1. Security

### S1 (High): A destroy and a recreate remove resources that do not have the label of the deployment

AGENT.md, "Resource labeling": "SAK removes only resources that carry the label `sak.pelle.io/name=<name>` of the
deployment." The code does not do this check.

- `DeploymentSpec::destroy_registry` (`cloud/src/hetzner/deployment.rs:800-814`) declares the resources by name only.
- `Registry::destroy_all_with` (`cloud/src/api/registry.rs:272-292`) and the recreate path of `apply_all_with`
  (`registry.rs:239-248`) call `Provisioner::destroy` with `as_lookup()`, which is the name.
- Each `destroy` finds the resource by name (`get_by_name`) and deletes it. No `destroy` reads the labels:
  `server/provisioner.rs:359`, `ssh_key/provisioner.rs:142`, `firewall/provisioner.rs:167`,
  `primary_ip/provisioner.rs:151`, `volume/provisioner.rs:133`, `dns_record/provisioner.rs:221`,
  `dns_zone/provisioner.rs:132`.
- The volume and primary IP `destroy` also remove the delete protection first (`volume/provisioner.rs:137-145`,
  `primary_ip/provisioner.rs:158-165`).
- Commit `7f13d25` ("remove only labeled resources") changed only `AGENT.md` and the FDR. It did not change code.

The password check does not stop this. For a destroy of a deployment that `discover` does not find,
`prepare` checks the password only against the hashes on DNS records (`core/deployments.rs:623-630`). With no record,
`check_password(&[], None, …)` accepts all passwords (`core/deployments.rs:468-482`).

**Failure scenario:** A Hetzner project has a server `web`, a firewall `web`, an ssh key `web` and primary IPs
`web-ipv4` and `web-ipv6`. SAK did not create them. A user types `sakctl destroy web --password <any 16 characters>
--delete-data`. sakctl deletes all of them and the volume `web-data`, also if they have delete protection.
The same occurs on a deploy: if a server with the name exists without the label, its diff is a recreate, and
`apply_all_with(recreate = true)` deletes it.

**Recommended change:** Make the label check part of each `destroy` (and of the destroy before a recreate). Read the
resource, compare its `sak.pelle.io/name` label with the name, and stop with an error if the label is missing or
different. The DNS record `apply` has this check already (`dns_record/provisioner.rs:169-177`); use the same
pattern. Do not remove the delete protection of a resource that does not have the label.

### S2 (Medium): A create or a deploy takes over resources that do not have the label of the deployment

The `apply` of each provisioner finds an existing resource by name and replaces its labels with the labels of the
deployment. It does not check whose resource it is.

- Firewall (`firewall/provisioner.rs:111-158`): `apply` replaces the labels, replaces the rules and removes the
  firewall from its label selectors.
- Volume (`volume/provisioner.rs:91-128`), primary IP (`primary_ip/provisioner.rs:96-148`), ssh key
  (`ssh_key/provisioner.rs:112-140`), server (`server/provisioner.rs:263-339`): `apply` replaces the labels.

`discover` finds deployments by the label only (`deployment.rs:331-385`). Thus `Intent::Create` with `existing = None`
continues when resources with the same names exist (`core/deployments.rs:618`).

**Failure scenario:** The user has a firewall `alpha` for other servers. `sakctl create alpha …` replaces its rules
with 22, 443 and ICMP, and the other servers lose their firewall. After the takeover, the firewall carries the
label of `alpha`, and a later destroy deletes it. A volume `alpha-data` of the user becomes the data volume of the
deployment.

**Recommended change:** In `prepare`, find each declared name (server, ssh key, firewall, volume, primary IPs). Stop
before the apply if a resource exists without the name label of the deployment, as `foreign_records` does for DNS
records (`core/deployments.rs:674-684`). Also check the label in each `apply`.

### S3 (Medium): Secrets are accepted as process arguments

`--hcloud-token` (`cli.rs:92`), `--password` (`cli.rs:102`) and `--smtp-password` (`cli.rs:125`) are clap arguments.
`hide_env_values` only hides the value of the environment variable in `--help`. A value given as an argument is in
`/proc/<pid>/cmdline`, in `ps` output for all local users, and in the shell history. The password of a deployment is
the root of the ssh client key and of the host key (`core/keys.rs:41-63`).

**Recommended change:** Accept these values only from the environment variables, from a file (`--password-file`), or
from a prompt on a terminal. If the arguments must stay, write a WARN line when a secret comes from an argument, and
say this in `--help` and in `UI-UX.md`.

### S4 (Medium): The password hash is public in the project, and the key derivation is weak for offline guessing

Each resource carries `sak.pelle.io/password-hash` (`deployment.rs:696-698`). All users and all read-only tokens of
the project can read it. The hash uses `Argon2::default()` (19 MiB, 2 passes, 1 lane) and a salt that is the name
(`core/keys.rs:68-75`). The only rule for the password is a length of 16 characters (`core/deployments.rs:582`). A
password such as `aaaaaaaaaaaaaaaa` is accepted. A correct guess gives the private client key with `derive_key`, and
thus root on the server.

**Recommended change:** Use stronger Argon2 parameters (for example 64 MiB or more, 3 passes) for the hash and the
keys. Add a version label (for example `sak.pelle.io/kdf=2`) so that old deployments still work. Reject weak
passwords with an entropy check, not only a length check. Record the remaining risk in "Accepted risks" in
`AGENT.md`.

### S5 (Medium): The private key stays in the temporary directory when the run is interrupted, and the path is predictable

`RunDir` (`core/provision.rs:174-201`) writes the unencrypted client key to `<temp>/sakctl-<name>-<pid>/id_ed25519`.
Only `Drop` removes it.

- No code handles SIGINT, SIGTERM or the close of the window (no `ctrl_c`, no `on_window_event`). If the user pushes
  Ctrl+C during the provisioning, or closes the window, the process stops and `Drop` does not run. The key stays in
  `/tmp`. The `docker run` child is not stopped either.
- `RunDir::create` (`provision.rs:180-188`) uses a predictable path in the shared `/tmp` and `create_dir_all`, which
  accepts a directory or a symbolic link that exists. Another local user can create
  `/tmp/sakctl-alpha-<pid>` as a symbolic link to a directory of the victim between the `remove_dir_all` and the
  `create_dir_all`. sakctl then writes the key and the configuration into that directory.
- AGENT.md: "Encrypt all data at rest". The key file is not encrypted and is not recorded as an accepted risk.

**Recommended change:** Create the directory with `mkdtemp` semantics (for example the `tempfile` crate with
`Builder::new().prefix("sakctl-").tempdir()`), which fails on a path that exists and uses a random name. Prefer
`$XDG_RUNTIME_DIR` (a tmpfs of the user, mode 0700) when it is set. Install a signal handler in the CLI and a window
close handler in the GUI that stop the container and remove the directory. Record the short-lived key file as an
accepted risk.

### S6 (Medium): The image of the tools is not pinned

`container::IMAGE` is `ghcr.io/pellepelster/sak-cli-tools:latest` (`core/container.rs:19`). sakctl pulls it on each
start and gives it the private root key of the server (`provision.rs:117`). If the image on ghcr.io is replaced
(a stolen registry token, a wrong push), the next run gives root on every server that sakctl provisions.

**Recommended change:** Pin the image by digest (`…@sha256:<digest>`) for each release of sakctl, or verify a
signature (cosign) before the run. Do the same for the canary image (`container.rs:16`).

### S7 (Low): The host private key is in the user data of the server

`user_data` (`core/deployments.rs:213-224`) puts the private host key into cloud-init. Hetzner keeps the user data,
and the metadata service on the server (`169.254.169.254`) gives it to every process on the server, also to a
container with an SSRF defect. A person with the host key can act as the server for later sakctl connections.

**Recommended change:** Hetzner cannot remove the user data from the metadata service after the first boot. Thus
block `169.254.169.254` for the containers on the server (the ansible role), or replace the host key after the first
verified connection. Record the decision in "Accepted risks".

### S8 (Low): The token check creates an ssh key without the label

`HetznerCloud::validate` (`core/providers/hetzner.rs:55-85`) creates `sakctl-token-check-<random>` without any
`sak.pelle.io/` label. AGENT.md says that every cloud resource carries the label. If the delete fails (network
error), the key stays, and no label tells it from other keys.

**Recommended change:** Add a label such as `sak.pelle.io/token-check=true`, and remove old keys with this label
on the next check.

### S9 (Low): `open_url` opens all https URLs

`open_url` (`gui/deployments.rs:21-35`) accepts each string that starts with `https://` and has no whitespace. The
page can thus open any site. The URL comes from the `sak.pelle.io/root-domain` label, which each user of the project
can change.

**Recommended change:** Accept only a URL that is equal to the `frontend_url` of a deployment that `list_deployments`
returned. Parse it with `url::Url` and reject control characters.

### S10 (Low): Structs with secrets derive `Debug`

`Cli`, `Command`, `Provider` and `DeploymentArgs` derive `Debug` (`cli.rs:39`, `51`, `89`, `96`). They hold the token,
the password and the SMTP password. No production code prints them now. But `core/deployments.rs:226-228` and
`:262-264` show that the project rule is "no Debug where a password is". A later `{:?}` or a failed `assert_eq!` writes
the secrets.

**Recommended change:** Implement `Debug` by hand and mask the secret fields, as `ProviderToken` does
(`core/providers/mod.rs:68-76`).

### S11 (Low): The log of the CLI contains raw IP addresses

AGENT.md: "Never log PII: … raw IPs". `list` writes the public IPv4 of the server (`deployment.rs:526-535`, printed at
`cli.rs:322-329`), and `provision.running` writes the host (`core/deployments.rs:852-858`). The IP of a server is not
the IP of a person, but the rule does not make this difference.

**Recommended change:** Make the rule in AGENT.md more precise (the IP addresses of the user's own servers are
permitted), or remove the addresses from the log.

### S12 (Low): The passwords from the environment go to the web view

`deployment_defaults` (`gui/deployments.rs:14-17`, `core/deployments.rs:354-365`) sends `SAK_PASSWORD` and
`SAK_SMTP_PASSWORD` to the JavaScript of the window. The CSP (`tauri.conf.json`, `default-src 'self'`) and the use of
`textContent` make this a small risk, but the secrets do not have to cross the IPC boundary.

**Recommended change:** Send only a flag "a password is set in the environment", and let Rust use the value when the
password field is empty.

### Areas examined with no finding

- Command and argument injection into docker, podman and ansible: all arguments go through `Command::args`, no shell.
  The name passes `check_name` (`deployment.rs:598-613`) before it goes into a path or the inventory. The root
  domain must be one of the zones of the project (`core/deployments.rs:121-158`). The storage device comes from the
  API.
- SSH host key verification: `StrictHostKeyChecking yes`, a `known_hosts` file for the IPv4 and the IPv6 address
  (`provision.rs:56-71`). The image does not turn off `host_key_checking` (`sak-cli/Dockerfile`).
- File modes of the run directory: 0700 for the directory, 0600 for the key (`provision.rs:186`, `:193`), and the
  mount is read-only (`provision.rs:117`). Only the problems in S5 apply.
- XSS in the UI: all values from the cloud go into `textContent`. The only `innerHTML` (`ui/checks.js:37`) is a fixed
  string.
- Masking of provider tokens in lists and in `Debug` (`core/providers/mod.rs:68-166`).
- `HetznerApi` does not derive `Debug`, so the token does not show in a log.

---

## 2. Correctness

### C1 (High): A deploy can delete the data volume and change the addresses

UI-UX.md: a deploy "may create its server anew; the data volume and the addresses stay". The code permits a
recreate of all resources:

- `prepare` sets `recreate = true` for each deploy (`core/deployments.rs:639`), and `apply_all_with(recreate, …)`
  uses it for all resources, not only for the server (`core/deployments.rs:807-810`, `registry.rs:239-248`).
- A different size or location of the volume is a recreate (`volume/provisioner.rs:60-73`). A different location of a
  primary IP is a recreate (`primary_ip/provisioner.rs:68-80`).
- The location of a deploy comes from the server only. When the server is missing, it comes from the argument
  (`core/deployments.rs:633-638`). The CLI default is `hel1` (`cli.rs:105`). The window uses `existing.location`,
  which is `null` without a server, and thus the first location of the list (`ui/create.js:276`, `:263`).
- The CLI applies the plan with no confirmation (`cli.rs:347-417`).

**Failure scenario:** A deployment is in `nbg1`. A deploy recreates its server, and the server create fails
(`resource_unavailable`). The server is now gone. The user runs `sakctl deploy alpha --root-domain pelle.io` again.
The location is `hel1`, the volume `alpha-data` in `nbg1` is a recreate, and sakctl deletes it with all data. The
primary IPs are recreated too, so the DNS names point to new addresses. The same occurs when the user made the volume
larger in the Hetzner console.

**Recommended change:** Permit a recreate only for the server (for example a `recreate` set of resource types, or a
flag on the resource). Stop with an error when the data volume or a primary IP needs a recreate. Take the location of
a deploy from the server, else from the data volume, else from the primary IPs, and never from a default.

### C2 (Medium): A CLI deploy does not read back the dns mode and the root domain

AGENT.md: "The wizard reads them back for a deploy." The window does this (`ui/create.js:281-283`). The CLI does not:
`--dns-mode` has the default `subdomain` (`cli.rs:109`), and `--root-domain` must be given again (`cli.rs:113`).
UI-UX.md documents the CLI default, so the two rule files do not agree.

**Failure scenario:** `beta` is a domain deployment of `pelle.io`. A script runs `sakctl deploy beta
--root-domain pelle.io`. sakctl changes `beta` to subdomain mode, creates `beta.pelle.io`, and removes the records `@`
and `*` of `pelle.io`. The production site stops. The plan writes the removal as WARN, but the command does not stop.

**Recommended change:** On a deploy, make `--dns-mode` and `--root-domain` optional with no default, and use the
labels of the deployment (`DiscoveredDeployment::dns_mode`, `root_domain`) when they are not given. Change UI-UX.md to
agree.

### C3 (Medium): The check for records of other owners looks only at the same record type

FDR-001: "SAK never changes a record without the label of the deployment. Such a record at a managed name stops the
deploy before anything is created." `foreign_records` (`deployment.rs:214-234`) compares the name **and** the type
(A or AAAA). A record of another type at a managed name does not stop the deploy.

**Failure scenario:** The zone has `www CNAME example.net`. The user creates the subdomain deployment `www`. The check
passes, sakctl creates the ssh key, the volume, the IPs, the firewall and the server, and the API then refuses the A
record next to the CNAME. The deploy fails after the costly part. With an `MX` or `TXT` record at the name, the
deployment adds A and AAAA records next to the records of the user.

**Recommended change:** In `foreign_records`, treat each rrset at a managed name without the name label of the
deployment as foreign, whatever its type. Add a test for CNAME, MX and TXT.

### C4 (Medium): The password check accepts all passwords when there is nothing to compare

`check_password` returns `Ok` when there is no hash and no ssh key (`core/deployments.rs:468-482`). The test at
`core/deployments.rs:962-963` makes this behavior a requirement. This occurs for a deployment that is "gone" but has
records without a hash (`:623-630`), and for a deployment of which only a volume or a primary IP from an old version
is left.

**Failure scenario:** With S1, any person with the token and a 16-character string can destroy resources. Without
S1, the same person can delete the labeled DNS records of a deployment that has no hash on them.

**Recommended change:** When there is nothing to check against, stop and ask for an explicit flag (for example
`--force` in the CLI and a second confirmation in the window).

### C5 (Medium): The API client retries only on HTTP 429

`HetznerApi::send` (`cloud/src/hetzner/api/mod.rs:272-295`) retries only `429 Too Many Requests`. A connection error,
a timeout, a 5xx answer, or the error codes `locked` and `conflict` (a resource with a running action) stop the apply
at once. The apply is not atomic, so the deployment stays half done until the next run. The ansible run has no
time limit (`provision.rs:129-164`); only the ssh connect has 30 seconds.

**Recommended change:** Retry idempotent requests (GET, PUT, DELETE) on connection errors and 5xx, and all requests
on `locked`, with the same backoff. Use the `RateLimit-Reset` header for 429. Add a time limit for the ansible run.

### C6 (Medium): The apply does not do the plan that the user saw

`plan` and `create` each call `prepare` and diff again (`core/deployments.rs:703`, `:762`, `registry.rs:225`). The
window shows a plan, and the apply can do a different thing if the project changed between the two calls. The CLI
prints the plan and applies at once, with no confirmation and no `--yes` flag (`cli.rs:347-417`). With C1, a recreate
of the data volume shows only as one INFO line `recreate`.

**Recommended change:** Give the plan to the apply (or a hash of the plan), and stop when the new diff is different.
In the CLI, ask for confirmation on a terminal when the plan removes or recreates a resource, and add `--yes` for
scripts.

### C7 (Low): The CLI writes "checking" for a DNS record that it removes

`working_key` (`cli.rs:431-439`) does not look at `resource.remove`. A stale record has the status `UpToDate`
(`core/deployments.rs:736-743`), so a deploy writes `checking dns record '…'` and then `destroyed dns record '…'`. The
window does this correctly (`ui/create.js:358`). UI-UX.md requires `destroying …`.

**Recommended change:** Give `remove` to `working_key` and return `cli.working_destroy` for it. See M1.

### C8 (Low): The CLI checks the trimmed token but uses the token as given

`ProviderTokensCheck::new` trims the token (`core/checks/provider_tokens.rs:18`). `Provider::tokens` uses the value
without trim (`cli.rs:176-178`). A token with a trailing `\r` (for example from a file with Windows line ends) passes
the check, and then each API call fails with an error about an invalid header.

**Recommended change:** Trim the token once in `Provider::tokens`, or use the tokens that the check returns.

### C9 (Low): The SMTP settings are accepted and then discarded

`Smtp` is checked (`core/deployments.rs:590-592`) but not used: `#[allow(dead_code)]` and the comment at
`core/deployments.rs:262-266` say so. The window and the CLI ask for the mail server, and UI-UX.md documents the
arguments. The user thinks that the backend will send mail.

**Recommended change:** Pass the values to the provisioning, or remove the fields from the window and the CLI until
the controller role uses them. If they stay, write a WARN line that they have no effect yet.

### C10 (Low): The warnings of `discover` are never shown

`discover` writes `warn!` when a deployment has a second server, ssh key or firewall (`deployment.rs:339-371`). sakctl
does not install a `tracing` subscriber (no `tracing` in `src-tauri/Cargo.toml`), so the warnings go nowhere. The
second resource is also not part of `verify_password` (`core/deployments.rs:433-458`), so a second server with a
different password hash does not stop a deploy.

**Recommended change:** Return the duplicates in `DiscoveredDeployment` and show them in `list` and in the plan.
Check the hashes of all resources with the name label.

### C11 (Low): The state of a deployment shows only three resources

`state` (`deployment.rs:446-562`) and `ResourceKind` (`deployment.rs:400-406`) cover the ssh key, the firewall and the
server. A deployment without its data volume, its primary IPs or its DNS records shows as `Ok`. Each resource is
also compared with a declaration made from itself, so the diff can only find the hash labels of the server.

**Recommended change:** Add the volume, the primary IPs and the DNS records to `ResourceKind` and to `state`, and
compare each resource with `DeploymentSpec` (from the labels on the resources).

### C12 (Low): Two runs on the same deployment are not prevented

Nothing stops two sakctl processes (two computers, or the CLI and the window) from a deploy or destroy of the same
deployment at the same time. The two applies can destroy and create the server at the same time.

**Recommended change:** Set a lock label (for example `sak.pelle.io/lock=<random>`) on the ssh key at the start, and
remove it at the end. Stop when another value is there.

### Areas examined with no finding

- Pagination: `list_all` follows `next_page` (`api/mod.rs:184-207`), also for zones and rrsets
  (`api/zones.rs:87-88`, `:269-270`).
- Action polling: `wait_for_action` stops on `error` and on the time limit (`api/mod.rs:230-269`).
- Volume detach after a server delete: `wait_for_detach` and `wait_for_volumes` (`server/provisioner.rs:375-412`).
- Primary IPs keep `auto_delete = false` (`primary_ip/provisioner.rs:116`, `:140`), so a server recreate keeps them.
- Idempotency of a create that failed half way: a second run finds what exists and continues (with the problems in
  S2 and C1).
- Ownership of DNS records in `apply` (`dns_record/provisioner.rs:169-177`) and the removal of only labeled records
  (`deployment_records`, `deployment.rs:195-209`).
- The root domain rules of FDR-001 decision 4 (`RootDomain::check`, `deployment.rs:151-174`) and their exit codes.

---

## 3. Code smells and simplifications

### M1 (Medium): The plan texts are made twice, in Rust and in JavaScript

`cli.rs:354-362` and `:431-439` (`what`, `working_key`) and `ui/create.js:183-191` and `:357-369` (`plannedText`,
`workingText`) have the same rules. They already differ (C7).

**Recommended change:** Let core put the text key into `PlannedResource` (for example `planned: "will_destroy"`,
`working: "working_destroy"`). The CLI and the page then only translate it.

### M2 (Low): `first_address` is in two places

`core/deployments.rs:900-907` and `cloud/src/hetzner/dns_record/provisioner.rs:72-79` are the same function. Make the
one in sak-cloud `pub` and use it from core.

### M3 (Low): A second Hetzner client outside the manager

`HetznerCloud` (`core/providers/hetzner.rs:8-86`) has its own `reqwest::Client`, its own API URL and its own error
parse, next to `HetznerApi` in sak-cloud. AGENT.md, "Naming": "the rest of the code asks it and holds no client of
its own." Move the token check to `HetznerApi` (`ssh_keys().create` and `delete`).

### M4 (Low): The names say "create" for create, deploy and destroy

`DeploymentManager::create` (`core/deployments.rs:755`), `CreateEvent` (`:188`), `NewDeployment` (`:397`) and the
command `create_deployment` (`gui/deployments.rs:63`) are used for all three intents. `create` is about 120 lines with
two separate paths. Rename them (for example `apply`, `DeploymentEvent`, `DeploymentRequest`), and split the destroy
path into its own function.

### M5 (Low): Comments that do not agree with the code

- `cli.rs:1-2` and `main.rs:17` name `list`, `create` and `deploy`, but not `destroy`.
- `cli.rs:378-379`: two comments for one statement; the first one belongs to `report`.
- `cloud/src/hetzner/mod.rs:26-27` and `core/keys.rs:65-67` say that the password hash is "on its server". It is on
  every resource (AGENT.md).
- `core/provision.rs:236` says "what the controller role writes", and `core/deployments.rs:263-264` says that "the
  provisioning does not run the controller role".
- `deployment.rs:593-594`: `USER_DATA` is "until the install of sak is there", but `prepare` always replaces it with
  the host key (`core/deployments.rs:673`).

### M6 (Low): One CLI command does the same work twice

`deploy` in `cli.rs` calls `plan` and then `create`, and each calls `prepare` (`core/deployments.rs:703`, `:762`).
Each `prepare` runs `discover` (5 list calls), lists all zones and the rrsets of each zone (1 + N calls), and runs
Argon2 three or four times. Keep the `Prepared` value from the plan for the apply (this also helps C6).

### M7 (Low): The labels of a deployment are collected in two places

`verify_password` (`core/deployments.rs:438-443`) and `DiscoveredDeployment::label` (`deployment.rs:75-83`) build the
same list of label maps. Add `DiscoveredDeployment::labels()` that returns all of them and use it in both places.

### Areas examined with no finding

- The boundary rule "core knows neither tauri nor clap" has a test (`core/mod.rs:15-43`), and the code follows it.
- GLOSSARY.md terms (name, dns mode, base domain, root domain) are used correctly in code, texts and
  `--help`.
- The color rule of `ui/style.css` and the plus sign rule have tests (`gui/mod.rs:67-107`).

---

## 4. Missing tests

1. A destroy and a recreate do not remove a resource without the name label, and a resource with the label of
   another deployment (S1). Use the wiremock helpers in `cloud/src/hetzner/test_support.rs`.
2. A create stops when a server, ssh key, firewall, volume or primary IP with the declared name exists without the
   label (S2).
3. A deploy never recreates the data volume or a primary IP, and takes the location from the volume when the server
   is missing (C1).
4. `foreign_records` treats a CNAME, MX or TXT rrset at a managed name as foreign (C3).
5. A CLI deploy without `--dns-mode` keeps the dns mode and the root domain of the deployment (C2).
6. `check_password` with no hash and no key refuses (C4). The test at `core/deployments.rs:962-963` asserts the
   opposite and must change.
7. `HetznerApi::send` retries on 5xx, on a connection error and on `locked` (C5). Now only 429 has a test
   (`cloud/src/hetzner/api/tests.rs`).
8. `RunDir::create` fails on a path that exists or is a symbolic link, and the directory is removed after an
   interruption (S5).
9. `working_key` returns the destroy text for a removed record (C7).
10. The CLI uses the trimmed token for the API calls (C8).
11. `open_url` refuses a URL that is not the frontend of a known deployment (S9).
