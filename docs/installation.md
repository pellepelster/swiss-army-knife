---
title: "Installation"
description: "Install sakctl, the program that sets up and maintains your SwissArmyKnife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakctl is the program on your computer that creates your SwissArmyKnife, keeps it up to date, makes backups and
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

sakctl is built for each change on the main branch. The downloads are the artifacts of the build:

1. Open the [builds of the main branch](https://github.com/pellepelster/swiss-army-knife/actions/workflows/swiss-army-knife.yml?query=branch%3Amain+is%3Asuccess)
   (you must be signed in to GitHub).
2. Open the newest build.
3. Below **Artifacts**, download the file of your system: `sakctl-linux`, `sakctl-macos` or `sakctl-windows`. Each
   is a zip file with one program, `sakctl` (`sakctl.exe` on Windows).

The program is not signed. macOS and Windows warn about this on the first start. Started without a command, sakctl
opens its window; `sakctl --help` shows the commands of its command line.

## Linux

1. Extract `sakctl` from the zip file, e.g. to `~/.local/bin`, and make it executable: `chmod +x sakctl`.
2. The window needs WebKitGTK 4.1. Most desktops have it already; else install it, e.g. `sudo apt install
   libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `sudo dnf install webkit2gtk4.1` (Fedora).
3. Start `sakctl`.

## macOS

The program works on Apple silicon and on Intel.

1. Extract `sakctl` from the zip file, e.g. to `/usr/local/bin`, and make it executable: `chmod +x sakctl`.
2. Start `sakctl` in the Terminal. macOS refuses the first start, because the program is not signed by a known
   developer.
3. Open **System Settings**, **Privacy & Security**. Below **Security**, click **Open Anyway** next to sakctl, and
   confirm. Then start `sakctl` again.

macOS asks this only once.

## Windows

1. Extract `sakctl.exe` from the zip file, e.g. to a folder in your user directory.
2. Start `sakctl.exe`. Windows SmartScreen can warn that the program is not known. Click **More info**, then **Run
   anyway**.

On Windows, use the window: the command line of `sakctl.exe` does not show its output. The window needs the Microsoft
Edge WebView2 runtime, which Windows 10 and 11 have.

## The first start

sakctl first checks that it can work: it looks for Docker or Podman, and it gets the latest image of its tools. The
first start takes some minutes for this download. Then sakctl asks for the API token of your Hetzner Cloud project
and shows your deployments.
