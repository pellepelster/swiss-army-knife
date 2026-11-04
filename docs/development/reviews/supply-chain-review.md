---
title: "Review: supply chain"
linkTitle: "Supply chain"
description: "The review of the signing of the releases and the images, and of the settings of the repository, for what could make a signature certify what was not built here."
weight: 6
---

# Review: supply chain

**Created:** 2026-11-04

Scope: the signing of the zips of sakctl and of the images with cosign (`mise run cli:release`, `image:sign`), their
verification in sakctl (`sakctl update`, `core/images.rs`, `SAKCTL-12` in [SYSTEM.md](../architecture/SYSTEM.md)), the
workflow (`.github/workflows/swiss-army-knife.yml`) and the settings of the repository on GitHub. The question: what
could make a signature of the workflow certify something that was not built from this repository.

What is good: the signatures are keyless, there is no signing key that could leak; sakctl checks them offline against
the trusted root it embeds, for the exact identity of the workflow at the tag of the release; the server gets the
images by their digests. Every action is pinned to a commit and the repository allows no other; the token of the
workflow reads only, but in the jobs that push or publish; the tags `v*` are protected by a ruleset and must be
signed; the release verifies against the root sakctl embeds; secret scanning with push protection is on; Pages
deploys only from the tags `v*`; there are no deploy keys and no webhooks.

Only the open findings are listed. Fixed with this review: the actions pinned (SHA pinning required, selected actions
only), `packages: write` only for the jobs that push, the ruleset of the tags `v*`, the keystore in the environment
`release` (but see `SC-1`) and the trusted root shared by CI and sakctl.

## Must fix

### `SC-1` The keystore of the android apps is still a secret of the repository

The workflow reads `ANDROID_KEYSTORE` and its passwords from the environment `release`, which only the job of a tag
`v*` gets. The secrets themselves are not there yet: they are still secrets of the repository, which every job of
every run on a push could read, and the environment has none, so the next tag fails at the signed apks.

Fix: `mise run github:secrets:update`, which writes them into the environment, then delete the four secrets of the
repository (`gh secret delete <name> --repo pellepelster/swiss-army-knife`).

### `SC-2` GitHub knows the signing key of the commits only as expired

The ruleset of main and the one of the tags `v*` require signed commits. The commits are signed with the GPG key
`7CB38B806115B466` (`pelle@pelle.io`), whose expiry was extended to 2027-07-07, but GitHub has the key with its old
expiry, 2023-06-30 (`https://github.com/pellepelster.gpg`). GitHub then shows the signatures as not verified, and
the rulesets may refuse the push.

Fix: upload the public key again (`gpg --armor --export 7CB38B806115B466`, Settings, SSH and GPG keys), so GitHub has
the new expiry.

## Should fix

### `SC-3` Dependabot alerts are off

The dependency graph does not raise alerts (`vulnerability-alerts` answers 404) and the security updates of
Dependabot are off. Renovate updates the dependencies every week, with a release age of 7 days, but without the
alerts a known vulnerability waits for the schedule, and Renovate's `vulnerabilityAlerts` has nothing to act on.

Fix: turn on the Dependabot alerts (Settings, Code security); the security updates can stay off, Renovate opens the
pull requests.

### `SC-4` The zips of sakctl are signed where they arrive, not where they are built

The three zips are built on three runners, go through `upload-artifact` and `download-artifact`, and are signed in
the job `release`. What changes a zip between the two, or runs in the job `release` before `cosign sign-blob`, gets
a valid signature. With the actions pinned, that takes a job of the same run that writes artifacts, which none does
today.

Fix: attest each zip in the job that builds it (`actions/attest-build-provenance`), and sign or check the
attestation in the job `release`.

### `SC-5` The server checks no signature of an image

sakctl verifies the images and hands the server their digests, so the server pulls exactly what sakctl verified.
The server itself checks nothing: podman reads only the older signatures of cosign, not the bundles of cosign 3.
Accepted in [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md); tracked in
[issue #145](https://github.com/pellepelster/swiss-army-knife/issues/145).

## Could fix

### `SC-6` `latest` can be set back to an older image of main

The signer of `latest` is the workflow at main, so every image main ever pushed is signed validly for `latest`.
Whoever can write to the registry can point `latest` at an old image with a known vulnerability, and a sakctl that
is no release (the only one that pulls `latest`) runs it. The releases are bound to `refs/tags/v<version>` and do not
have this problem.

Fix, if the builds of the developers should be protected: check the commit in the certificate
(`1.3.6.1.4.1.57264.1.3`) against the commit of the sakctl, or refuse an image of main older than the sakctl.

### `SC-7` The signature names the digest, not the image

cosign signs the digest; its statement names no image. A registry writer could point `sak-cli-tools:1.2.3` at the
signed digest of `sak-backend:1.2.3`, and sakctl would accept it. It fails rather than runs foreign code (nobody but
the workflow can make a signed image), but it would fail in a confusing place.

Fix: sign with an annotation of the name (`cosign sign -a image=<name>`) and check it in sakctl.

### `SC-8` A tag set with `SAK_DOCKER_IMAGE_TAG` that no workflow pushes is pulled unchecked

`latest-rc`, a commit or any other tag has no signer, so sakctl pulls it by its tag (`SAKCTL-12`). Meant for the
images a developer pushes; whoever can set the variable on the machine could also replace sakctl.

### `SC-9` Gradle has no verification of its dependencies

Neither the backend nor the android apps have `gradle/verification-metadata.xml` or lockfiles. The versions are fixed
in the build files and Maven Central does not change a published version, so this is about a compromised mirror, a
plugin portal or a dependency whose own dependencies move. Rust (`Cargo.lock`) and npm (`package-lock.json`) are
locked.

Fix: `./gradlew --write-verification-metadata sha256` in both builds, and Renovate's lockfile maintenance for them.

### `SC-10` The tauri command line is installed without a lockfile

`mise install npm:@tauri-apps/cli` installs it by its version, its dependencies as npm resolves them on that day,
in the job that builds the binaries of sakctl, which become the signed zips.

Fix: install it from `sak-cli/package.json` with its lockfile (`npm ci`), or pin it in a `package-lock.json` of its
own.
