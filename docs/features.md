---
title: "Features"
description: "The apps of Swiss Army Knife and what sakctl does for you."
weight: 2
---

# Features

Swiss Army Knife is your own micro-cloud for a family or a small group: a few apps on a server of yours, set up and
looked after by one program, sakctl.

![The apps of a SAK instance](screenshots/web-apps-desktop.png)

## Privacy first

Nothing sends data to a third party unless you turn it on and agree to it first. The apps load nothing from outside,
and all data stays on your own server.

The AI features, which discover the text in a photo or a recording of a note, are off until an admin turns them on in
the settings. The settings say what the apps would send and to whom, and they need the API key of your own OpenRouter
account; providers that collect the data are not asked.

![The settings of the AI features, turned off](screenshots/web-settings-ai-desktop.png)

## sakctl

One program on your computer sets up and looks after your SAK instance, in the way that suits you.

**The UI, for beginners:** sakctl guides you from the token of your Hetzner Cloud project to a running SAK instance.
Before anything changes, it shows a plan of what it will create, change or delete.

![Installing a SAK instance with the sakctl UI](screenshots/sakctl-ui.mp4)

**The command line, for advanced users:** the same as commands in a terminal, for scripts and automation.

![The help of the sakctl command line](screenshots/sakctl-cli-help.png)

Both do the same:

- **Install, update and remove** a SAK instance; a deploy brings it to the version of sakctl.
- **Backup and restore** all data of a SAK instance as one encrypted file on your computer.
- **Server maintenance:** upgrade the packages of the server and reboot it, without interrupting a backup.
- **Self-update:** sakctl replaces itself with its newest release when you ask for it.

![A SAK instance in the sakctl UI](screenshots/sakctl-ui-deployment.png)

## Across all devices

Every app works in the browser of a desktop and of a phone; on a phone you can install it on the home screen. The
notes and the grocery lists also have a native Android app.

On a desktop, the notes are a wall of post-its:

![The notes in the browser of a desktop](screenshots/web-notes-desktop.png)

On a phone, the same app in its browser:

![The notes in the browser of a phone](screenshots/web-notes-mobile.png)

And the notes as a native Android app:

![The notes in the Android app](screenshots/android-notes.png)

## Light and dark

Every app follows the light or the dark mode of the device, in the browser as well as in the Android apps; the
post-its keep their colors in both.

![The notes in the light mode](screenshots/web-notes-desktop.png)

![The notes in the dark mode](screenshots/web-notes-dark-desktop.png)

## The apps

### Notes

Post-its from text, photos and voice recordings, sorted with labels and in colors. When you turn it on, the text in a
photo or a recording is written into the note on request. The notes also have an Android app, which downloads from
`sak.<your domain>/notes/download/app`.

![A note with a photo](screenshots/web-notes-photo-desktop.png)

### Grocery lists

Shopping lists, private or shared with everyone of the SAK instance. A checked item goes after a moment, unless you undo
it, and stays as bought: the eye in the toolbar shows the bought items too. What you added to a list before comes back
as a suggestion on that list. Each list groups its items by its own categories, with a name and an icon, which its
owner changes in the settings of the list. Every change shows at once on every device that has the list open, also in
the Android app, which downloads from `sak.<your domain>/grocery/download/app`.

### Static hosting

Websites from S3 buckets, at their own domain or at a subdomain of the SAK instance. Each site shows its requests and
its traffic.

![A bucket of static hosting](screenshots/web-hosting-bucket-desktop.png)

### Redirects

Domains that lead to another address: an external domain, or the site of a bucket.

![Redirects](screenshots/web-redirects-desktop.png)

## Users

- **Single user:** you sign in with the password of the SAK instance; no mail server is needed.
- **Multiple users:** your users sign in with a link by e-mail, you add them by their address. Data is either private
  to the one who made it or shared with everyone of the SAK instance, no roles per item, no groups.

![The login by e-mail](screenshots/web-login-email-desktop.png)

Next: how [sakctl and your SAK instance](overview.md) belong together, or [install sakctl](installation.md).
