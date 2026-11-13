---
title: "Privacy Policy of the Android Apps"
linkTitle: "Privacy"
description: "The privacy policy of the Android apps SAK Notes, SAK Grocery List and SAK Todo: the apps send your data only to the SAK instance you operate."
weight: 10
---

# Privacy policy of the Android apps

This policy applies to these Android apps of Swiss Army Knife (SAK):

- SAK Notes (`io.pelle.sak.notes`)
- SAK Grocery List (`io.pelle.sak.grocery`)
- SAK Todo (`io.pelle.sak.todo`)

Effective date: 12 November 2026

## Summary

The developer of the apps does not collect, keep or share your personal data. The apps are clients of a SAK instance,
a server that you operate, or that a person you know operates for you. The developer operates no server for the apps
and gets no data from them.

## Where your data goes

The apps send your data only to the SAK instance whose address you enter, encrypted (HTTPS). Your lists, notes, photos
and voice recordings are kept on that SAK instance. Its operator, not the developer of the apps, is responsible for
the data on it.

## No tracking

Neither the apps nor the web frontend of your SAK instance use advertising, analytics, crash reporting, tracking pixels
or other tracking. The web frontend loads nothing from a third party.

## Data on your phone

The apps keep the address of your SAK instance and your sign-in session on your phone, encrypted. This data is not in
a backup and does not move to a new phone. When you sign out, the app deletes the session; when you uninstall the
app, Android deletes all of this data.

## Permissions

- **Internet:** to connect to your SAK instance.
- **Microphone (SAK Notes only):** only when you start a voice recording.
- **Camera:** only when you tap **Scan QR code** or take a photo. The app reads the QR code that connects it to your
  SAK instance on your phone; it keeps no picture of it and sends none.
- **Photos:** only the photos that you take with the camera app, select or share with the app.

## Exceptions

- **AI features.** In SAK Notes, they read the text of a photo or a voice recording of a note; in SAK Grocery List,
  the items in a photo of a recipe or a shopping list. They are off until the administrator of your SAK instance turns
  them on with the API key of their own OpenRouter account. Then only you start them, with the robot button, for one
  photo or recording at a time, and your SAK instance, not the app, sends it to OpenRouter.
- **Downloads from GitHub.** Apps that are not from Google Play come from the GitHub releases of SAK: the
  installation link and each update you start download from GitHub, which then sees the IP address of your phone, but
  no other data. Apps from Google Play do not do this: Google Play installs and updates them.
- **Google Play.** When you install an app from Google Play, the privacy policy of Google applies to Google Play.

## Delete your data

Your data is on your SAK instance. Delete it in the apps, or ask the operator of your SAK instance to delete it. To
delete the data on your phone, uninstall the app.

## Changes and contact

When this policy changes, the new version is on this page with a new effective date. For questions, open an issue at
[github.com/pellepelster/swiss-army-knife](https://github.com/pellepelster/swiss-army-knife/issues).
