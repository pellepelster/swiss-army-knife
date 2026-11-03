---
title: "Installation"
description: "Install sakctl, the program that sets up and maintains your Swiss Army Knife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakctl is the program on your computer that creates your SAK instance, keeps it up to date, backs it up and
destroys it. It has a UI and a command line. There is no installer: sakctl is a single program.

## What you need

- **Docker or Podman.** sakctl runs its tools in a container. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/)
  or [Podman Desktop](https://podman-desktop.io/) and start it before sakctl. On Linux, the `docker` or `podman`
  package of your distribution is enough.
- **A Hetzner Cloud project and its API token.** Your server runs at Hetzner Cloud. Create a project in the
  [Hetzner Cloud Console](https://console.hetzner.cloud/), then an API token with read and write access (Security,
  API tokens). sakctl asks for the token on each start and does not store it.
- **A domain** whose DNS zone is in the same Hetzner project.

## Download

Each release is on the [release page](https://github.com/pellepelster/swiss-army-knife/releases). The commands below
download sakctl for your system, check its SHA-256 checksum and extract it; if the checksum does not match, they stop.

The program is not signed. Without a command, sakctl opens the UI; `sakctl --help` lists the commands.

## Linux

The UI needs WebKitGTK 4.1, which most desktops have. If not, install it, e.g. `sudo apt install
libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `sudo dnf install webkit2gtk4.1` (Fedora). Then, in a terminal:

{{< install platform="linux" >}}

Start `sakctl`. If the shell does not find it, add `~/.local/bin` to your `PATH`. On its first start the UI adds
**Swiss Army Knife** to the applications of your desktop, so you can start it from the menu.

## macOS

The program works on Apple silicon and on Intel. In the Terminal:

{{< install platform="macos" >}}

Start `~/.local/bin/sakctl`. Downloaded with `curl`, it starts without a warning.

If you download the zip file in a browser instead, macOS refuses the first start. Open **System Settings**, **Privacy &
Security**, click **Open Anyway** next to sakctl below **Security**, and confirm. macOS asks only once.

## Windows

In PowerShell:

{{< install platform="windows" >}}

Start `sakctl.exe` in `%LOCALAPPDATA%\Programs\sakctl`. If Windows SmartScreen warns about it, click **More info**,
then **Run anyway**.

On Windows, sakctl is two programs, both in `%LOCALAPPDATA%\Programs\sakctl`:

- **`sakctl.exe`** opens the UI. Start it in the Explorer, for example with a double click. On the command line it
  shows no output.
- **`sakctl-cli.exe`** is the command line. Use it in PowerShell or the command prompt, for example
  `& "$env:LOCALAPPDATA\Programs\sakctl\sakctl-cli.exe" --help`. Where this page and the help say `sakctl`, type
  `sakctl-cli.exe`. Without a command, it also opens the UI, but with a console window next to it.

An update, in the UI or with `sakctl-cli.exe update`, replaces both programs. If `sakctl-cli.exe` is missing, run the
commands above again. The UI needs the Microsoft Edge WebView2 runtime, which Windows 10 and 11 have.

## Update

sakctl only updates when you ask. In the UI, click **Check for updates** in the footer, then **Update to** the new
version, then **Restart**. On the command line, `sakctl update` updates and `sakctl update --check` only checks. sakctl
downloads the newest release from GitHub, verifies its SHA-256 checksum and replaces itself.

The next deploy updates the server of the SAK instance to the same version.

## The first start

sakctl first looks for Docker or Podman and downloads the image of its tools, which takes a few minutes the first
time. Then it asks for the API token of your Hetzner Cloud project and shows your SAK instances.

Next: [first steps](first-steps.md), to create your first SAK instance.
