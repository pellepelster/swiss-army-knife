# Swiss Army Knife

<!-- docs-link: the docs home leaves it out, it is the docs -->
<h2 align="center">
  <a href="https://pellepelster.github.io/swiss-army-knife/features/">✨ See all features</a>
  &nbsp;·&nbsp;
  <a href="https://pellepelster.github.io/swiss-army-knife/">📖 Read the documentation</a>
</h2>
<!-- /docs-link -->

Swiss Army Knife is a self-hosted, privacy first micro-cloud for families and small groups: a few apps on your own
server, set up and looked after by one program, sakctl.

<!-- the docs home shows the README up to here, its features page has the rest in detail -->

<p align="center">
  <img src="docs/screenshots/web-apps-desktop.png" alt="The apps of a SAK instance" width="80%">
</p>

## The apps

- **Notes:** post-its from text, photos and voice recordings, with labels and colors.
- **Grocery lists:** shopping lists, private or shared, grouped by categories, updated live on every device.
- **Static hosting:** websites from S3 buckets, at their own domain or at a subdomain.
- **Redirects:** domains that lead to another address, with automatic TLS certificates.

The notes and the grocery lists also have an Android app.

## Privacy first

Nothing goes to a third party unless you turn it on. The apps load nothing from outside, and your data stays on your
server. The AI features are off until an admin turns them on with their own OpenRouter API key.

## Users

- **Single user:** the admin signs in with the password. No mail server needed.
- **Multiple users:** the admin adds users by their e-mail address. Needs a mail server.
- **No accounts:** no sign-up and no passwords to remember, users sign in with a link sent by e-mail.
- **Simple sharing:** data is private, or shared with everyone. No roles, no groups.

## sakctl

One program on your computer sets up and looks after your SAK instance.

**The UI, for beginners:** guides you from your Hetzner Cloud token to a running SAK instance, and shows a plan before
it changes anything.

<p align="center">
  <img src="docs/screenshots/sakctl-ui.gif" alt="Installing a SAK instance with the sakctl UI" width="560">
</p>

**The command line, for advanced users:** the same as commands, for scripts and automation.

<p align="center">
  <img src="docs/screenshots/sakctl-cli-help.png" alt="The help of the sakctl command line" width="80%">
</p>

Both can install, update and remove a SAK instance, back up and restore all its data as one encrypted file, maintain
its server, and update themselves.

## Across all devices

Every app works in the browser on a desktop and on a phone, installs on the home screen (PWA), and follows the light or
dark mode of the device.

<p align="center">
  <img src="docs/screenshots/web-notes-desktop.png" alt="The notes in the browser of a desktop" width="80%">
</p>

<p align="center">
  <img src="docs/screenshots/web-notes-mobile.png" alt="The notes in the browser of a phone" width="30%">
  &nbsp;
  <img src="docs/screenshots/android-notes.png" alt="The notes in the Android app" width="30%">
  &nbsp;
  <img src="docs/screenshots/android-notes-dark.png" alt="The notes in the Android app in the dark mode" width="30%">
</p>

## Get started

[Install sakctl](https://pellepelster.github.io/swiss-army-knife/installation/), or read
[all features](https://pellepelster.github.io/swiss-army-knife/features/).
