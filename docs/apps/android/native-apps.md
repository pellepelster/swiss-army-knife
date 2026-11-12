---
title: "Install the Native Android Apps"
linkTitle: "Native apps"
description: "Where to get the links to SAK Notes, SAK Grocery List and SAK Todo, and what to answer when Android or Play Protect shows a warning."
weight: 5
---

# Install the native Android apps

SAK Notes, SAK Grocery List and SAK Todo are Android apps for your SAK instance. You download them from the GitHub release of
the version your SAK instance runs. Android does not know apps from outside of Google Play, so it shows warnings
during the installation. This page tells you what to answer.

## Get the links

Each link downloads the app in the version that your SAK instance runs. Use one of these:

- **The page of the Android apps:** on the index page of your SAK address, tap **Android apps**. Or open
  `<SAK address>/downloads` on the phone; this page opens without a sign-in. It shows a download link for each app.
- **An e-mail from your admin:** on the **Users** page, an admin opens the ⋮ menu of a user and selects **Send links to
  the Android apps**. The user gets an e-mail with a link to each app. This needs a mail server.
- **The download page of one app:** `<SAK address>/notes/download/app`, `<SAK address>/grocery/download/app` or
  `<SAK address>/todo/download/app` starts
  the download at once.

If your SAK instance runs no release, there are no links. The page then links the
[releases on GitHub](https://github.com/pellepelster/swiss-army-knife/releases); use the release with the version of
your SAK instance.

## Install an app

Open the link on the phone. The words of the warnings are different on each Android version and phone; the steps
are the same.

1. **The browser warns about the file.** The browser says that this type of file can harm your device. Tap
   **Download anyway**.
2. **Open the downloaded file.** Tap the download in the notification or in the downloads of the browser.
3. **Allow the browser to install apps.** Android says that your phone is not allowed to install unknown apps from
   this source. Tap **Settings**, turn on **Allow from this source** and go back.
4. **Install.** Tap **Install**.
5. **Play Protect warns about the app.** Play Protect does not know the developer of the app, because the app does
   not come from Google Play. Answer as follows:
   - **"App scan recommended"** or **"Send app for security check?"**: tap **Install without scanning** or **Don't
     send**. **Scan app** or **Send** is also safe: Google gets the app file, not your data.
   - **"Unsafe app blocked"** or **"Blocked by Play Protect"**: tap **More details**, then **Install anyway**.
6. **Open the app and connect it.** The fastest way is the QR code: in the browser, sign in to your SAK instance,
   open the ⋮ menu next to your name on the start page and select **Connect an app**. In the app, tap **Scan QR
   code** and point the camera at the code. The app is signed in, without typing the address or a password. The code
   is valid for 2 minutes and only once; whoever scans it signs in as you, so don't show it to others. Instead of the
   code, you can also enter your SAK address and sign in with an e-mail link or the admin password.

After the installation, you can turn off **Allow from this source** for the browser again.

## Updates

When your SAK instance runs a newer version, the app offers an update at the top of its start page. When you install
the first update, Android asks if the app may install apps: turn on **Allow from this source** for the app, then
confirm the update. From Android 12 on, the next updates install without a confirmation.

## Is the app genuine?

Every app of a release is signed with the same key. Android installs an update only when the key is the same as the
key of the installed app, so a changed app does not install over a genuine one. Each app of a release also has a
SHA-256 checksum, the file `sak-<app>-<version>.apk.sha256` next to it.
