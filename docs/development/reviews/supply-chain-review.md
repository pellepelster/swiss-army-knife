---
title: "Review: supply chain"
linkTitle: "Supply chain"
description: "The review of the signing of the releases and the images, and of the settings of the repository, for what could make a signature certify what was not built here."
weight: 6
---

# Review: supply chain

**Created:** 2026-11-04

**Checked again:** 2026-11-23, every finding listed is still open; the fixed ones are in the history of git. `SC-3`
is fixed: the Dependabot alerts are on (`vulnerability-alerts` answers 204), the security updates stay off. `SC-4`
is fixed for the zips of sakadmin and sakbackup: each is signed in the job that builds it (`mise run desktop:sign`),
the job `release` has no id token and only checks the signatures before it publishes.

Scope: the signing of the zips of sakadmin and of the images with cosign (`mise run desktop:sign`,
`admin-cli:release`, `image:sign`), their verification in sakadmin (`sakadmin update`, `core/images.rs`, `SAKADM-12`
in [SYSTEM.md](../architecture/SYSTEM.md)), the workflow (`.github/workflows/swiss-army-knife.yml`) and the settings of the
repository on GitHub. The question: what could make a signature of the workflow certify something that was not built
from this repository.

What is good: the signatures are keyless, there is no signing key that could leak; sakadmin checks them offline against
the trusted root it embeds, for the exact identity of the workflow at the tag of the release; the server gets the
images by their digests. Every action is pinned to a commit and the repository allows no other; the token of the
workflow reads only, but in the jobs that push or publish; the tags `v*` are protected by a ruleset and must be
signed; the release verifies against the root sakadmin embeds; secret scanning with push protection is on; Pages
deploys only from the tags `v*`; there are no deploy keys and no webhooks.

Only the open findings are listed. Fixed with this review: the actions pinned (SHA pinning required, selected actions
only), `packages: write` only for the jobs that push, the ruleset of the tags `v*`, the keystore in the environment
`release` and the trusted root shared by CI and sakadmin.

## Could fix

### `SC-6` `latest` can be set back to an older image of main

The signer of `latest` is the workflow at main, so every image main ever pushed is signed validly for `latest`.
Whoever can write to the registry can point `latest` at an old image with a known vulnerability, and a sakadmin that
is no release (the only one that pulls `latest`) runs it. The releases are bound to `refs/tags/v<version>` and do not
have this problem.

Fix, if the builds of the developers should be protected: check the commit in the certificate
(`1.3.6.1.4.1.57264.1.3`) against the commit of the sakadmin, or refuse an image of main older than the sakadmin.

### `SC-7` The signature names the digest, not the image

cosign signs the digest; its statement names no image. A registry writer could point `sak-admin-cli-tools:1.2.3` at the
signed digest of `sak-backend:1.2.3`, and sakadmin would accept it. It fails rather than runs foreign code (nobody but
the workflow can make a signed image), but it would fail in a confusing place.

Fix: sign with an annotation of the name (`cosign sign -a image=<name>`) and check it in sakadmin.
