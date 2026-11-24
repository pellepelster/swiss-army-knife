---
title: "UI and UX: desktop apps"
linkTitle: "UI/UX desktop apps"
description: "How the desktop apps look and behave, in addition to the rules of all apps."
weight: 24
---

# UI and UX: desktop apps

How the desktop apps look and behave. It adds to the rules of all apps in [UI-UX.md](UI-UX.md); the rules are written as
[RULES.md](../RULES.md) says.

## Rules

- `DESKTOP-1` **A sign-in is remembered.** A desktop app that signs in at a deployment (sakbackup) remembers the
  SAK address and the sign-in. At its next start it opens signed in, without the sign-in screen. When the deployment
  no longer accepts the sign-in, or does not answer, the app shows the sign-in screen with the SAK address filled
  in and says why. A sign-out forgets the sign-in and keeps the SAK address. On a computer without a keychain the
  app remembers the SAK address only.
- `DESKTOP-2` **Sign-in through the browser.** A desktop app has no sign-in form of its own. Its first screen asks
  for the SAK address and has one button. The button opens the browser of the user, where the user signs in as in
  the web app and confirms the connection of the app. The app and the page in the browser show the same code; the
  user confirms only when the codes are the same. The app waits for the browser for a limited time and has a button
  to cancel. A sign-in that fails shows the cause on the sign-in screen. The app is signed in at one deployment
  at a time. A release of the app connects with https only.
- `DESKTOP-3` **The SAK addresses are remembered on the computer.** A desktop app remembers the SAK addresses of its
  successful sign-ins, on the computer of the user only, the last one first. The sign-in screen shows the last
  address. A failed sign-in is not remembered.
- `DESKTOP-4` **Update in the footer, account in the navigation.** A desktop app has a footer below every view
  with its version, one button and a line of text. The button checks for a newer version, installs it on a second
  click and offers a restart after that; the line says whom the check asks before anything is asked, and what it
  found. Nothing is asked before the user clicks. A desktop app that signs in (sakbackup) has its navigation on the
  left; at its bottom a menu with the name of the user holds "Sign out".
