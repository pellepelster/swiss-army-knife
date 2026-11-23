---
title: "Installation"
description: "Install sakadmin, the program that sets up and maintains your Swiss Army Knife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakadmin creates, updates, backs up and destroys your SAK instance. It has a UI and a command line in a single
program; on Linux there are also a deb, an rpm and an AppImage of it.

## What you need

- **Docker or Podman.** sakadmin runs its tools in a container. Install
  [Docker Desktop](https://www.docker.com/products/docker-desktop/) or [Podman Desktop](https://podman-desktop.io/)
  and start it before sakadmin. On Linux, the `docker` or `podman` package of your distribution is enough.
- **A Hetzner Cloud project and its API token.** Create a project in the
  [Hetzner Cloud Console](https://console.hetzner.cloud/), then an API token with read and write access (Security,
  API tokens). sakadmin asks for the token at each start and does not store it.
- **A domain** whose DNS zone is in the same Hetzner project.

## Download

The commands below download sakadmin from the [release page](https://github.com/pellepelster/swiss-army-knife/releases),
check its SHA-256 checksum and install it. They stop if the checksum does not match.

sakadmin is not signed for macOS and Windows, so they do not know it. Each file of the release is signed with
[cosign](https://docs.sigstore.dev/cosign/), though: [check the signature](#check-the-signature) to see that the
release workflow of this repository built it. Without a command, sakadmin opens the UI; `sakadmin --help` lists the
commands.

## Linux

sakadmin comes four ways for Linux (x86_64). Pick one; each command checks the SHA-256 checksum before it installs.

**The program** works on any distribution. The UI needs WebKitGTK 4.1, which most desktops have. If not, install
it, e.g. `sudo apt install libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `sudo dnf install webkit2gtk4.1` (Fedora). Then,
in a terminal:

{{< install platform="linux" >}}

Start `sakadmin`. If the shell does not find it, add `~/.local/bin` to your `PATH`. At its first start, the UI adds
**Swiss Army Knife** to the applications menu of your desktop. It updates itself.

**A deb package** for Debian and Ubuntu installs WebKitGTK with it:

{{< install platform="deb" >}}

**An rpm package** for Fedora and openSUSE (`sudo zypper install` instead of `dnf` there):

{{< install platform="rpm" >}}

The packages put `sakadmin` in `/usr/bin` and **Swiss Army Knife** in the applications menu. They are updated like
any package: sakadmin tells you when there is a new release, and you install its deb or rpm the same way.

**An AppImage** brings WebKitGTK and everything else it needs:

{{< install platform="appimage" >}}

Start `~/.local/bin/sakadmin.AppImage`, for example `sakadmin.AppImage --help` for the command line. At its first
start, the UI adds **Swiss Army Knife** to the applications menu. It updates itself, the file stays where it is.

## macOS

For Apple silicon and Intel. In the Terminal:

{{< install platform="macos" >}}

Start `~/.local/bin/sakadmin`. Downloaded with `curl`, it starts without a warning.

If you download the zip file in a browser, macOS refuses the first start. Open **System Settings**, **Privacy &
Security**, click **Open Anyway** next to sakadmin below **Security**, and confirm. macOS asks only once.

## Windows

In PowerShell:

{{< install platform="windows" >}}

Start `sakadmin.exe` in `%LOCALAPPDATA%\Programs\sakadmin`. If Windows SmartScreen warns, click **More info**, then
**Run anyway**.

On Windows, sakadmin is two programs in `%LOCALAPPDATA%\Programs\sakadmin`:

- **`sakadmin.exe`** opens the UI, for example with a double click in the Explorer. It shows no output on the command
  line.
- **`sakadmin-cli.exe`** is the command line, for PowerShell or the command prompt, for example
  `& "$env:LOCALAPPDATA\Programs\sakadmin\sakadmin-cli.exe" --help`. Where the docs say `sakadmin`, type
  `sakadmin-cli.exe`. Without a command, it opens the UI with a console window next to it.

An update, in the UI or with `sakadmin-cli.exe update`, replaces both programs. If `sakadmin-cli.exe` is missing, run
the commands above again. The UI needs the Microsoft Edge WebView2 runtime, which Windows 10 and 11 have.

## Check the signature

The checksum shows that the download is complete; the signature shows who made it: the release workflow of
[pellepelster/swiss-army-knife](https://github.com/pellepelster/swiss-army-knife) at the tag of the release, with a
short-lived [Sigstore](https://www.sigstore.dev/) certificate, so there is no key that could leak. With
[cosign installed](https://docs.sigstore.dev/cosign/system_config/installation/), run this in the directory of the
downloaded file, on Linux or macOS:

{{< install platform="linux" signature="true" >}}

{{< install platform="macos" signature="true" >}}

For the deb, the rpm or the AppImage, the same command with its name, e.g.:

{{< install platform="deb" signature="true" >}}

On Windows, in PowerShell:

{{< install platform="windows" signature="true" >}}

cosign answers `Verified OK`. Any other answer means the release did not make the file: delete it.

## Update

sakadmin updates only when you ask. In the UI, click **Check for updates** in the footer, then **Update to** the new
version, then **Restart**. On the command line, `sakadmin update` updates and `sakadmin update --check` only checks.
sakadmin downloads the newest release from GitHub, verifies its checksum and its signature, and replaces itself; an
AppImage replaces its file. Installed from a deb or an rpm, sakadmin only tells you that there is a new release:
download its package and install it as above.

The next deploy updates the SAK instance to the same version.

## The first start

sakadmin looks for Docker or Podman and downloads the image of its tools; the first time, this takes a few minutes.
Then it asks for the API token of your Hetzner Cloud project and shows your SAK instances.

Next: [first steps](first-steps.md), to create your first SAK instance.
