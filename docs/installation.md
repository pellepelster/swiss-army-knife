---
title: "Installation"
description: "Install sakadm, the program that sets up and maintains your Swiss Army Knife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakadm creates, updates, backs up and destroys your SAK instance. It has a UI and a command line, and it is a single
program without an installer.

## What you need

- **Docker or Podman.** sakadm runs its tools in a container. Install
  [Docker Desktop](https://www.docker.com/products/docker-desktop/) or [Podman Desktop](https://podman-desktop.io/)
  and start it before sakadm. On Linux, the `docker` or `podman` package of your distribution is enough.
- **A Hetzner Cloud project and its API token.** Create a project in the
  [Hetzner Cloud Console](https://console.hetzner.cloud/), then an API token with read and write access (Security,
  API tokens). sakadm asks for the token at each start and does not store it.
- **A domain** whose DNS zone is in the same Hetzner project.

## Download

The commands below download sakadm from the [release page](https://github.com/pellepelster/swiss-army-knife/releases),
check its SHA-256 checksum and extract it. They stop if the checksum does not match.

sakadm is not signed for macOS and Windows, so they do not know it. Each zip file is signed with
[cosign](https://docs.sigstore.dev/cosign/), though: [check the signature](#check-the-signature) to see that the
release workflow of this repository built it. Without a command, sakadm opens the UI; `sakadm --help` lists the
commands.

## Linux

The UI needs WebKitGTK 4.1, which most desktops have. If not, install it, e.g. `sudo apt install
libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `sudo dnf install webkit2gtk4.1` (Fedora). Then, in a terminal:

{{< install platform="linux" >}}

Start `sakadm`. If the shell does not find it, add `~/.local/bin` to your `PATH`. At its first start, the UI adds
**Swiss Army Knife** to the applications menu of your desktop.

## macOS

For Apple silicon and Intel. In the Terminal:

{{< install platform="macos" >}}

Start `~/.local/bin/sakadm`. Downloaded with `curl`, it starts without a warning.

If you download the zip file in a browser, macOS refuses the first start. Open **System Settings**, **Privacy &
Security**, click **Open Anyway** next to sakadm below **Security**, and confirm. macOS asks only once.

## Windows

In PowerShell:

{{< install platform="windows" >}}

Start `sakadm.exe` in `%LOCALAPPDATA%\Programs\sakadm`. If Windows SmartScreen warns, click **More info**, then
**Run anyway**.

On Windows, sakadm is two programs in `%LOCALAPPDATA%\Programs\sakadm`:

- **`sakadm.exe`** opens the UI, for example with a double click in the Explorer. It shows no output on the command
  line.
- **`sakadm-cli.exe`** is the command line, for PowerShell or the command prompt, for example
  `& "$env:LOCALAPPDATA\Programs\sakadm\sakadm-cli.exe" --help`. Where the docs say `sakadm`, type `sakadm-cli.exe`.
  Without a command, it opens the UI with a console window next to it.

An update, in the UI or with `sakadm-cli.exe update`, replaces both programs. If `sakadm-cli.exe` is missing, run the
commands above again. The UI needs the Microsoft Edge WebView2 runtime, which Windows 10 and 11 have.

## Check the signature

The checksum shows that the download is complete; the signature shows who made it: the release workflow of
[pellepelster/swiss-army-knife](https://github.com/pellepelster/swiss-army-knife) at the tag of the release, with a
short-lived [Sigstore](https://www.sigstore.dev/) certificate, so there is no key that could leak. With
[cosign installed](https://docs.sigstore.dev/cosign/system_config/installation/), run this in the directory of the
zip file, on Linux or macOS:

{{< install platform="linux" signature="true" >}}

{{< install platform="macos" signature="true" >}}

On Windows, in PowerShell:

{{< install platform="windows" signature="true" >}}

cosign answers `Verified OK`. Any other answer means the release did not make the zip file: delete it.

## Update

sakadm updates only when you ask. In the UI, click **Check for updates** in the footer, then **Update to** the new
version, then **Restart**. On the command line, `sakadm update` updates and `sakadm update --check` only checks.
sakadm downloads the newest release from GitHub, verifies its checksum and its signature, and replaces itself.

The next deploy updates the SAK instance to the same version.

## The first start

sakadm looks for Docker or Podman and downloads the image of its tools; the first time, this takes a few minutes.
Then it asks for the API token of your Hetzner Cloud project and shows your SAK instances.

Next: [first steps](first-steps.md), to create your first SAK instance.
