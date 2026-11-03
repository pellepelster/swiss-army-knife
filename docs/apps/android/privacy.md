---
title: "Privacy Policy of the Android Apps"
linkTitle: "Privacy"
description: "The privacy policy of the Android apps SAK Grocery List and SAK Notes: the apps send your data only to the SAK deployment you operate."
weight: 10
---

# Privacy policy of the Android apps

This policy applies to these Android apps of Swiss Army Knife (SAK):

- SAK Grocery List (`io.pelle.sak.grocery`)
- SAK Notes (`io.pelle.sak.notes`)

Effective date: 3 November 2026

## Summary

The developer of the apps does not collect, keep or share your personal data. The apps are clients for a SAK
deployment, a server that you operate yourself, or that a person you know operates for you. The developer operates
no server for the apps and gets no data from them.

## Where your data goes

The apps send your data only to the SAK deployment whose address you enter. The connection is encrypted (HTTPS). Your
lists, notes, photos and voice recordings are kept on that deployment. The operator of the deployment is responsible
for the data on it, not the developer of the apps.

## No tracking

SAK uses no tracking, neither in the apps nor in the web frontend of your deployment: no advertising, no analytics,
no crash reporting, no tracking pixels and no other tracking. The web frontend loads nothing from a third party.

## Data on your phone

The apps keep the address of your deployment and your sign-in session on your phone, encrypted. This data is not
included in a backup and does not move to a new phone. When you sign out, the app deletes the session. When you
uninstall the app, Android deletes all of this data.

## Permissions

- **Internet:** to connect to your deployment.
- **Microphone (SAK Notes only):** to record a voice note, only when you start a recording.
- **Photos:** the apps get only the photos that you take with the camera app or select, or that you share with the
  app.

## Exceptions

- **AI features.** The AI features read the text of a photo or a voice recording. They are off until the administrator
  of your deployment turns them on with the API key of their own OpenRouter account. When they are on, your deployment,
  not the app, sends the photo or the recording to OpenRouter. The app shows the name OpenRouter before it sends it.
- **Updates of the apps from GitHub.** The apps that you install from the GitHub releases of SAK download an update
  from GitHub when you start the update. GitHub then sees the IP address of your phone. The apps from Google Play do
  not do this: Google Play updates them.
- **Google Play.** When you install an app from Google Play, the privacy policy of Google applies to Google Play.

## Delete your data

Your data is on your deployment. Delete it in the apps, or ask the operator of your deployment to delete it. To delete
the data on your phone, uninstall the app.

## Changes and contact

When this policy changes, the new version is on this page with a new effective date. For questions, open an issue at
[github.com/pellepelster/swiss-army-knife](https://github.com/pellepelster/swiss-army-knife/issues).
