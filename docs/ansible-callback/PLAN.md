# Plan: Ansible events pushed to sakctl

**Status:** put aside. The behavior and the decisions are in
[FDR-006](../fdr/FDR-006-provisioning-events.md); this is how to build it. Modeled on blcks
(`blcks-ansible/ansible_collections/blcks/cloud/plugins/callback/http_notify.py` and
`blcks-ansible/src/main/kotlin/de/solidblocks/cloud/ansible/events/AnsibleEvent.kt`).

## 1. The callback in the collection `pelle_io.sak`

- `ansible/plugins/callback/http_notify.py`, ported from blcks: `play_start`, `task_start`, `ok` (with
  `changed`), `failed` (with `msg`), `skipped`, `unreachable` and `stats` (the counts of each host).
- Python's `urllib` only, a timeout of 2 seconds; a failed post is a warning, never an error of the run.
- Enabled by `ANSIBLE_CALLBACKS_ENABLED=pelle_io.sak.http_notify`. It reads `SAK_EVENTS_URL`, and the token
  from a file in the read-only run directory (`/run/sak/events_token`) instead of an environment variable, so
  the token is in neither `docker inspect` nor the list of processes.
- It ships in the image of the tools with the collection; the Dockerfile does not change.

## 2. The endpoint in sakctl (`core/provision.rs`)

- `ProvisionManager::provision` starts a small http server for the run on `127.0.0.1:0`, the port chosen by the
  system. The provisioning runs on threads, so a small synchronous server fits (e.g. `tiny_http`, a new
  dependency).
- A token of 32 random bytes (`getrandom`) for each run, written as `events_token` (mode 600) next to the
  inventory.
- Only `POST /events` with `Authorization: Bearer <token>` (compared in constant time) and a body of at most
  64 KiB; anything else 401 or 404.
- The body is parsed into an `AnsibleEvent` enum (serde, tagged like blcks' `AnsibleEvent.kt`) and goes into the
  channel that already merges stdout and stderr, so events and raw lines keep their order. The server ends with
  the container.

## 3. Reaching the endpoint from the container

- `-e SAK_EVENTS_URL=http://<host>:<port>/events` and `-e ANSIBLE_CALLBACKS_ENABLED=...`.
- `<host>`: on Linux `--network host` and `127.0.0.1`; Docker Desktop on macOS and Windows
  `host.docker.internal`; a Podman machine `host.containers.internal`. `ContainerRuntime` knows docker from
  podman, `cfg!(target_os)` the platform.
- Never `0.0.0.0` or the bridge address: that would open the endpoint to the network or to every container.

## 4. Showing it

- `CreateEvent` gets `ProvisionPlay`, `ProvisionTask` (name; ok, changed, skipped or failed) and
  `ProvisionStats`; `Output` stays for the raw lines.
- The command line prints one line for each finished task (`[changed] Install the backend`); the `msg` of a
  failed task is the error of the run; the raw output only with `--show-log`.
- The window shows the task that runs and the finished tasks with the leds of the theme (ok green, changed
  yellow, failed red, skipped grey); the raw output behind a "Show log" toggle.
- Without any event (e.g. the loopback blocked), both fall back to the raw output, as today.

## 5. Tests

- Rust: the server refuses a missing or wrong token and a body too large, parses each event (the JSON samples of
  blcks), the provisioning passes events on in order.
- Image: `cli:image:test` runs a playbook of one task against `localhost` in the image, with the callback pointed
  at a small stub server, so the plugin loads and posts.
- E2E: `SakctlTest` asserts that `create` printed task lines (`[ok]`, `[changed]`).

## 6. Docs

- "Provisioning" in `docs/ARCHITECTURE.md` names the endpoint on the loopback; FDR-006 turns Active.

## Open

- Should the backend of a deployment receive the same events later, as the controller of blcks does
  (`AnsibleEventsController`)? Then the event format belongs in one place both read.
