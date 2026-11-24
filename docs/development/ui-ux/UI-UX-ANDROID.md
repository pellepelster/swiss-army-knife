---
title: "UI and UX: Android apps"
linkTitle: "UI/UX Android apps"
description: "How the Android apps look and behave, in addition to the rules of all apps."
weight: 23
---

# UI and UX: Android apps

How the Android apps look and behave. It adds to the rules of all apps in [UI-UX.md](UI-UX.md); the rules are written as
[RULES.md](../RULES.md) says.

## Rules

- `ANDROID-1` **Material 3, the colors of the phone.** The Android apps follow the light or dark mode of the system
  and take the colors of the wallpaper where Android has them.
- `ANDROID-2` **One screen at a time.** The deployment, the sign-in, the first screen of the app (e.g. the overview
  of the lists, the wall of the notes) and the screens it leads to. The first screen has the name of the app as its
  title; at its top right are its main create action and the menu with the check for updates and the sign-out.
  Every other screen of a signed in user has a back arrow at the top left with its own title; the back of the system
  does the same.
- `ANDROID-3` **Pull down to reload.** Every screen that shows data of the deployment loads it again when the user
  pulls it down.
- `ANDROID-4` **Delete asks in a dialog.** On Android a Delete asks in a dialog over the screen, not inline in the row:
  "Delete &lt;name&gt;?", Keep as the safe default (`UX-4.1`) and Delete in red. A row of a phone has no room for the question
  next to its name and its menu.
