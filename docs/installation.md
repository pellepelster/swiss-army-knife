---
title: "Installation"
description: "Install sakctl, the program that sets up and maintains your Swiss Army Knife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakctl is the program on your computer that creates your Swiss Army Knife, keeps it up to date, makes backups and
removes it again. It has a window, and on Linux and macOS also a command line. There is no installer: sakctl is one
program.

## What you need

- **Docker or Podman.** sakctl runs its tools in a container. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/)
  or [Podman Desktop](https://podman-desktop.io/) and start it before sakctl. On Linux, the `docker` or `podman`
  package of your distribution is enough.
- **A Hetzner Cloud project and its API token.** Your server runs at Hetzner Cloud. Create a project in the
  [Hetzner Cloud Console](https://console.hetzner.cloud/), then an API token with read and write access (Security,
  API tokens). sakctl asks for the token on each start and does not store it.
- **A domain** whose DNS zone is in the same Hetzner project.

## Download

sakctl is built for each release, and published on the
[release page](https://github.com/pellepelster/swiss-army-knife/releases) of the repository. The commands below
download the program of your system, check its SHA-256 checksum and extract it. When the checksum does not match,
the commands stop: do not use the file.

The program is not signed. Started without a command, sakctl opens its window; `sakctl --help` shows the commands of
its command line.

## Linux

The window needs WebKitGTK 4.1. Most desktops have it already; else install it, e.g. `sudo apt install
libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `sudo dnf install webkit2gtk4.1` (Fedora). Then, in a terminal:

{{< install platform="linux" >}}

Start `sakctl`. When the shell does not find it, add `~/.local/bin` to your `PATH`.

When its window opens, sakctl adds **Swiss Army Knife** with its icon to the applications of your desktop
(`~/.local/share/applications/sakctl.desktop`), so the taskbar shows its icon and you can start it from the menu.

## macOS

The program works on Apple silicon and on Intel. In the Terminal:

{{< install platform="macos" >}}

Start `~/.local/bin/sakctl`. A program that `curl` downloads starts without the warning of macOS about an unknown
developer.

When you download the zip file in a browser instead, macOS refuses the first start. Open **System Settings**,
**Privacy & Security**. Below **Security**, click **Open Anyway** next to sakctl, and confirm. macOS asks this only
once.

## Windows

In PowerShell:

{{< install platform="windows" >}}

Start `sakctl.exe` in `%LOCALAPPDATA%\Programs\sakctl`. Windows SmartScreen can warn that the program is not known.
Click **More info**, then **Run anyway**.

On Windows, use the window: the command line of `sakctl.exe` does not show its output. The window needs the Microsoft
Edge WebView2 runtime, which Windows 10 and 11 have.

## Update

sakctl does not look for updates on its own. To update it, use the button in the footer of the window: **Check for
updates**, then **Update to** the new version, then **Restart**. The line next to the button says what happens. On
the command line, `sakctl update` does the same, and `sakctl update --check` only shows if a new version is
available; start sakctl again to use the new version. sakctl asks GitHub for the newest release, checks the SHA-256
checksum of the download and replaces itself.

A deploy with the new version also updates the server of the deployment to it.

## The first start

sakctl first checks that it can work: it looks for Docker or Podman, and it gets the latest image of its tools. The
first start takes some minutes for this download. Then sakctl asks for the API token of your Hetzner Cloud project
and shows your deployments.
