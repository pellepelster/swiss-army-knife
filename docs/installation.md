---
title: "Installation"
description: "Install sakctl, the app that sets up and maintains your SwissArmyKnife, on Linux, macOS or Windows."
weight: 5
---

# Installation

sakctl is the app on your computer that creates your SwissArmyKnife, keeps it up to date, makes backups and removes it
again. It has a window, and on Linux and macOS also a command line.

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
   is a zip file.

The files are not signed. macOS and Windows warn about this on the first start.

## Linux

The zip file contains the program `sakctl` and three installers. Use the one that fits your distribution:

- **Debian, Ubuntu:** install the `.deb` file, e.g. `sudo apt install ./SwissArmyKnife_0.1.0_amd64.deb`.
- **Fedora, openSUSE:** install the `.rpm` file, e.g. `sudo dnf install ./SwissArmyKnife-0.1.0-1.x86_64.rpm`.
- **Every other distribution:** make the `.AppImage` file executable (`chmod +x`) and start it.

SwissArmyKnife is then in the menu of your desktop. For the command line, start `sakctl --help`.

## macOS

The zip file contains a disk image (`.dmg`) and the program `sakctl` for the command line. Both work on Apple silicon
and on Intel.

1. Open the `.dmg` file and drag **SwissArmyKnife** into **Applications**.
2. Start SwissArmyKnife. macOS refuses the first start, because the app is not signed by a known developer.
3. Open **System Settings**, **Privacy & Security**. Below **Security**, click **Open Anyway** next to
   SwissArmyKnife, and confirm.

macOS asks this only once. For the command line, the program is in the app:
`/Applications/SwissArmyKnife.app/Contents/MacOS/sakctl --help`.

## Windows

The zip file contains two installers and the program `sakctl.exe`. Use one of the installers:

- the `.msi` file, or
- the `-setup.exe` file.

Windows SmartScreen can warn that the app is not known. Click **More info**, then **Run anyway**.

SwissArmyKnife is then in the start menu. On Windows, use the window: the command line of `sakctl.exe` does not show
its output.

## The first start

sakctl first checks that it can work: it looks for Docker or Podman, and it gets the latest image of its tools. The
first start takes some minutes for this download. Then sakctl asks for the API token of your Hetzner Cloud project
and shows your deployments.
