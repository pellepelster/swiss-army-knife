---
title: "Install the Native Android Apps"
linkTitle: "Native apps"
description: "Where to get the links to SAK Notes, SAK Grocery List and SAK Todo, and what to answer when Android or Play Protect shows a warning."
weight: 5
---

# Install the native Android apps

SAK Notes, SAK Grocery List and SAK Todo come from the GitHub release of the version your SAK instance runs. Android
does not know apps from outside of Google Play and shows warnings during the installation. This page tells you what to
answer.

## Get the links

Each link downloads the app in the version of your SAK instance:

- **The page of the Android apps:** tap **Android apps** on the index page of your SAK address, or open
  `<SAK address>/downloads` on the phone (no sign-in needed). It has a link for each app.
- **An e-mail from your admin:** on the **Users** page, the admin opens the ⋮ menu of a user and selects **Send links
  to the Android apps**. This needs a mail server.
- **The download of one app:** `<SAK address>/notes/download/app`, `<SAK address>/grocery/download/app` or
  `<SAK address>/todo/download/app`.

If your SAK instance runs no release, there are no links. Use the
[release on GitHub](https://github.com/pellepelster/swiss-army-knife/releases) with the version of your SAK instance.

## Install an app

Open the link on the phone. The words of the warnings differ on each phone and Android version; the steps are the
same.

1. **The browser warns about the file.** Tap **Download anyway**.
2. **Open the downloaded file** from the notification or the downloads of the browser.
3. **Allow the browser to install apps.** Tap **Settings**, turn on **Allow from this source** and go back.
4. **Install.** Tap **Install**.
5. **Play Protect warns about the app,** because it does not know apps from outside of Google Play:
   - **"App scan recommended"** or **"Send app for security check?"**: tap **Install without scanning** or **Don't
     send**. **Scan app** or **Send** is also safe: Google gets the app file, not your data.
   - **"Unsafe app blocked"** or **"Blocked by Play Protect"**: tap **More details**, then **Install anyway**.
6. **Open the app and connect it.** The fastest way is the QR code: sign in to your SAK instance in the browser, open
   the ⋮ menu next to your name on the start page and select **Connect an app**. In the app, tap **Scan QR code** and
   point the camera at the code. The code works once and for 2 minutes; whoever scans it signs in as you, so do not
   show it to others. You can also enter your SAK address and sign in with an e-mail link or the admin password.

After the installation, you can turn off **Allow from this source** for the browser again.

## Updates

When your SAK instance runs a newer version, the app offers an update at the top of its start page. At the first
update, turn on **Allow from this source** for the app, then confirm. From Android 12 on, later updates install
without a confirmation.

## Is the app genuine?

All apps of a release are signed with the same key, and Android installs an update only with the key of the installed
app, so a changed app cannot replace a genuine one. Each app also has a SHA-256 checksum next to it,
`sak-<app>-<version>.apk.sha256`.
