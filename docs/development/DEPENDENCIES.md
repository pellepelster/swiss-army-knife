---
title: "Dependency Updates"
linkTitle: "Dependencies"
description: "How Renovate updates the dependencies of SAK, which updates merge automatically and which updates are manual."
weight: 25
---

# Dependency Updates

The [Renovate](https://docs.renovatebot.com/) GitHub app updates the dependencies of SAK. Its configuration is
[`renovate.json`](../../renovate.json) at the root of the repository. Renovate opens a pull request for each update or
group of updates. The workflow tests each pull request as it tests every other pull request.

## What Renovate updates

| File | Dependencies |
|---|---|
| `mise.toml` (`[tools]`) | The tools: java, node, rust, the tauri cli, hugo, go, nextest, svu, ansible-core, pipx. |
| `gradle/libs.versions.toml`, `android/gradle/libs.versions.toml` | The libraries and the plugins of the two Gradle builds. |
| `gradle/wrapper/`, `android/gradle/wrapper/` | Gradle. |
| `sak-frontend/package.json`, `sak-theme/package.json` | The npm packages and npm (`packageManager`). |
| `sak-cli/src-tauri/Cargo.toml`, `sak-cli/cloud/Cargo.toml` | The crates. |
| `*/Dockerfile` | The base images. In `sak-cli/Dockerfile` also the `ansible` package (the `# renovate:` comment). |
| `ansible/roles/*/defaults/main.yml` | The images with a pinned tag (`<name>_image: docker.io/<image>:<tag>`). |
| `ansible/requirements.yml` | The Ansible collections. |
| `config/local-compose.yaml` | The images of the local environment. |
| `site/go.mod` | Go. |
| `.github/workflows/` | The actions and the runners. |

To add a version in a Dockerfile `ARG`, write a comment on the line before it:

```dockerfile
# renovate: datasource=pypi depName=ansible
ARG ANSIBLE_VERSION=11.13.0
```

## When

- Renovate opens pull requests on Monday before 06:00 (Europe/Berlin), at most 5 at the same time.
- Renovate uses a release only when it is 7 days old. A release that is removed again in this time does not come in.
- On the first day of each month Renovate updates the lock files (`Cargo.lock`, `package-lock.json`).
- The Dependency Dashboard (an issue of the repository) shows all open, pending and manual updates.

## Groups

- One pull request for each ecosystem with its minor and patch updates, for example `mise minor and patch`.
- Before version 1.0, a minor update can break the API. These updates come in their own group (`cargo 0.x minor`).
- **kotlin**: Kotlin of the backend build and of the android build.
- **tauri**: The tauri cli in `mise.toml` and the crates. They must have the same version.
- **playwright**: The Java library of sak-e2e and the npm package of sak-frontend. They must have the same browsers.
- **ansible**: The `ansible` package of the tools image, `ansible-core` in `mise.toml` and the collections, also the
  major updates. Each version of `ansible` contains one version of `ansible-core` (`ansible` 11 contains
  `ansible-core` 2.18).
- Each major update is a pull request of its own.

## Automatic merge

Renovate merges a pull request when all checks of the workflow pass. This applies only to:

- the minor and patch updates of the actions,
- the minor and patch updates of the npm dev dependencies,
- the minor and patch updates of the crates.

All other pull requests are manual: the images, the Gradle libraries, the npm dependencies, tauri, playwright,
ansible, the `0.x` minor updates and all major updates. The workflow does not run the integration tests and the e2e
tests. Before you merge one of these pull requests, run the tests that apply (`mise run backend:test:integration`,
`mise run cli:test:integration`, `mise run cli:test:e2e`).

## Manual updates

Renovate does not update these dependencies. Update them manually:

- **The Android SDK** in `mise.toml`: the URL of the command-line tools and its sha1, `ANDROID_BUILD_TOOLS`, the
  platform and the system image of the emulator.
- **The server image** `debian-13` of the Hetzner servers in sak-cloud.
- **A major version of Java**: the `java` tool in `mise.toml`, the Gradle toolchain (`sak.java-conventions`) and
  `openjdk<version>-jre` in `sak-backend/Dockerfile` together.
- **The Alpine packages** in the images (for example `openjdk25-jre`, `caddy`). They come with the version of Alpine.

## Test the configuration

```sh
mise run renovate:dry-run
```

The task validates `renovate.json` and runs Renovate on the checkout without a change. It shows the pull requests
that Renovate opens and the dependencies that it skips. The full log is in `build/renovate/renovate.log`. Without a
GitHub token, Renovate skips the actions and the tools that come from GitHub releases. The task reads the token from
`pass github/pellepelster/pat/admin`, `GITHUB_COM_TOKEN` replaces it.

## Run Renovate manually

```sh
mise run renovate:run                        # creates and updates the pull requests now
RENOVATE_DRY_RUN=full mise run renovate:run  # shows what it does, without a change
```

The task runs Renovate on GitHub with the configuration of `renovate.json` on `main`, at once and not on its schedule.
The lock files are also updated. The pull requests come from the owner of the token
(`pass github/pellepelster/pat/admin`, `GITHUB_COM_TOKEN` replaces it). The token must have the scopes `repo` and
`workflow`. Renovate merges a pull request automatically only during a run, so run the task again when the checks pass.
Use the task or the Renovate app, not both: Renovate does not change a branch that another user changed.

The TOML parser of Renovate does not accept an inline table on more than one line. In `mise.toml`, write the
environment of a task as a table of its own (`[tasks."<task>".env]`).
