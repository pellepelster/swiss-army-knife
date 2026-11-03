---
title: "Review: user documentation"
linkTitle: "User docs"
description: "The review of the documentation for users: facts that are wrong, words that are not the same, and the wording."
weight: 6
---

# Review: user documentation

**Created:** 2026-11-03

Scope: the documentation that users read: `README.md`, `docs/overview.md`, `docs/installation.md`,
`docs/features.md` and `docs/apps/android/` (`INDEX.md`, `native-apps.md`, `web-apps.md`, `privacy.md`), and the
pages of the site in `site/content/`. Checked against the code, `i18n/texts.yml` and the
[glossary](../GLOSSARY.md).

What is good: the pages are short, each has one task, the steps use the labels of the UI (checked in
`i18n/texts.yml`), and the Android pages say what to answer to each warning.

## Must fix

### ~~`UD-1` The Android pages say that the native apps do not come from Google Play~~

**No change:** the apps are not yet in Google Play, so the Android pages are right. `privacy.md` already covers
Google Play for the review of the store. When the apps are in Google Play, name it first on the Android page and on
`native-apps.md`.

`docs/apps/android/INDEX.md` ("The native apps do not come from Google Play") and `native-apps.md` (the GitHub
release is the only source) contradict `privacy.md` ("The apps from Google Play do not do this: Google Play updates
them") and the `play` flavor of the apps (`SakAndroidAppPlugin.kt`). When the apps are in Google Play, the section
"Apps from outside of Google Play" leads users away from the easiest way.

Fix: when the apps are in Google Play, name Google Play first on the Android page and on `native-apps.md`, and keep
the warnings for the apps from GitHub. Until then, `privacy.md` can stay as it is.

### ~~`UD-2` The privacy policy names GitHub only for updates, but the first download also comes from GitHub~~

**Fixed:** the privacy policy names the download and the updates of the apps from GitHub; the features list every
download from GitHub (sakctl, the image of its tools, the images of the server, the Android apps) and the two images
from Docker Hub.

`privacy.md` ("Updates of the apps from GitHub") says that GitHub sees the IP address of the phone when the app
downloads an update. The links of `native-apps.md` (`<SAK address>/downloads`, `/<app>/download/app`, the e-mail)
also download the APK from GitHub (`apkUrl` in `sak-frontend/src/app/shared/app-download.ts`), so GitHub also sees
the IP address at the first installation.

Fix: say in `privacy.md` that the download and the updates come from GitHub.

### ~~`UD-3` The privacy policy and the features do not name the items that the AI features read in a photo~~

**Fixed:** the privacy policy and the features name both (the text of a photo or a recording of a note, the items in
a photo for a grocery list) and say that only the user starts them, with the robot button, one photo or recording at
a time.

`features.md` ("The AI features, which read the text of a photo or a recording") and `privacy.md` ("The AI features
read the text of a photo or a voice recording") do not include `GROCERY-16`: SAK Grocery List sends a photo to
OpenRouter to read the items in it. For the privacy policy of SAK Grocery List this is a missing data flow.

Fix: name both in `privacy.md` and `features.md`: the text of a photo or a recording of a note, and the items in a
photo for a grocery list.

### ~~`UD-4` The overview gives the wrong address of the apps~~

**Fixed:** the overview names both DNS modes and the SAK address of each.

`overview.md` says "Its apps are at `sak.<domain>`". With the dns mode `subdomain`, the default, the address is
`sak.<name>.<root domain>`.

Fix: "Its apps are at its SAK address, for example `https://sak.family.example.org`", or name both dns modes.

## Consistency

### ~~`UD-5` Deployment and SAK instance~~

**Fixed:** the pages for users say "SAK instance" everywhere, the glossary says so.

The glossary says: the documentation and sakctl say **deployment**, the texts of the apps say **SAK instance**. The
pages mix them for the same thing:

- for sakctl: `overview.md` and `installation.md` say "deployment"; `features.md` and `README.md` say "SAK instance"
  ("Install, update and remove a SAK instance").
- for the users of the apps: `INDEX.md`, `native-apps.md` and `web-apps.md` say "SAK instance"; `privacy.md` says
  "deployment" 12 times.

Fix: one rule for the pages and add it to the glossary, for example: the pages for the users of the apps
(`docs/apps/`) say "SAK instance", the pages for the admin who runs sakctl say "deployment". Then change
`privacy.md`, and `features.md` and `README.md` in the sakctl sections.

### ~~`UD-6` Remove, destroy~~

**Fixed:** the installation, the features and the README say "destroy".

The glossary says that a deployment is **destroyed**. `installation.md` ("creates your Swiss Army Knife … and
removes it"), `features.md` ("Install, update and remove") and `README.md` ("install, update and remove") say
"remove"; `overview.md` says "destroys".

Fix: "destroy" everywhere.

### ~~`UD-7` Log in, sign in~~

**Fixed:** the features say "Sign in with just your e-mail address".

`features.md` says "Log in with just your e-mail address" (the text and the alt text of the screenshot). All other
pages and the UI say "sign in".

Fix: "Sign in with just your e-mail address".

### ~~`UD-8` The names of the Android apps~~

**Fixed:** the features and the README say "native Android app" where they mean it; the privacy policy names SAK
Notes first. The labels of the UI ("Android apps") and the title of the privacy policy stay.

Since the web apps have their own page, "the Android app" is not clear: `README.md` ("The notes and the grocery lists
also have an Android app") and `features.md` ("also have an Android app", "In the Android app") mean the native apps.
`privacy.md` lists "SAK Grocery List and SAK Notes", all other pages "SAK Notes and SAK Grocery List".

Fix: "native Android app" where it is the native app; the same order of the names everywhere.

### `UD-9` The screenshots of the web apps show the old name

`web-apps.md` says "The screenshots show SAK Notes"; they show "Notes", because the SAK instance of the screenshots
(sak.krawallbu.de) runs a release from before the names of the manifests changed.

Fix: take the screenshots again after that SAK instance is updated.

## Wording

### ~~`UD-10` The sentence about the web apps in the features~~

**Fixed:** the link is no longer in parentheses, the lines are at most 120 characters.

`features.md`: "and installs on the home screen (PWA, install the web apps on Android)" puts a link in
parentheses, and the line is longer than 120 characters.

Fix: "Every app works in the browser on a desktop and on a phone. On Android, you can [install it on the home
screen](apps/android/web-apps.md) as a web app (PWA)."

### ~~`UD-11` The source of the native apps on the Android page~~

**Fixed:** the Android page says "you download them with a link of your SAK instance".

`INDEX.md`: "you download them from the GitHub release of your SAK instance". A SAK instance has no GitHub release;
the app comes from the release of the version that the SAK instance runs, by a link of the SAK instance.

Fix: "you download them with a link of your SAK instance".

### ~~`UD-12` The home of the documentation describes the development~~

**Fixed:** the home describes the documentation for users; the development is one section of it.

`site/content/_index.md`: the description ("how it is built, how it looks and behaves, its words and its
features") and the heading "Documentation — How Swiss Army Knife is built and why" describe the documentation for
developers. The page is the first page for users.

Fix: a description for users (what SAK is, how to install it, the apps), and the development as one entry of the list.

## README

### `UD-13` "Nothing goes to a third party" is not true for the downloads

`README.md` and `features.md` start the privacy with "Nothing goes to a third party unless you turn it on". sakctl,
the server and the native Android apps download from GitHub and Docker Hub, which see the IP address
(`features.md`, "Downloads from GitHub").

Fix: "Your data never goes to a third party unless you turn it on", and in the README one sentence on the downloads
from GitHub with a link to the features.

### `UD-14` "No accounts" contradicts the users that the admin adds

`README.md` and `features.md`: "**No accounts:** no sign-up and no passwords to remember". The admin adds each user
by the e-mail address, which is an account, and the admin signs in with a password.

Fix: "**No passwords for users:** no sign-up, users sign in with a link sent by e-mail."

### `UD-15` The README does not say what the AI features do

`README.md` says only that the AI features are off until an admin turns them on. `features.md` says what they read
and that only the user starts them.

Fix: one sentence as in `features.md`: they read the text of a photo or a recording of a note and the items in a
photo for a grocery list, and only the user starts them.

### `UD-16` The README does not lead to the first steps and does not say what you need

"Get started" links the installation and the features, not [the first steps](../../first-steps.md). The README
never says that SAK needs a Hetzner Cloud project and a domain with its DNS zone at Hetzner.

Fix: "Get started" lists what you need (a Hetzner Cloud project, a domain, Docker or Podman) and links the
installation and the first steps.

### `UD-17` The README does not link the installation of the web apps

"Across all devices" says that every app "installs on the home screen (PWA)" without a link;
`features.md` links [the web apps](../../apps/android/web-apps.md).

Fix: the same link as in `features.md`.

### `UD-18` The command line is not on every system

"**The command line, for advanced users:** the same as commands" does not say that the command line of
`sakctl.exe` shows no output on Windows (`installation.md`).

Fix: "on Linux and macOS".

## sakctl texts (`i18n/texts.yml`)

### `UD-19` The UI and the command line of sakctl say "deployment"

The documentation for users says "SAK instance" (`UD-5`), but sakctl, which the documentation shows step by step,
says "deployment": 49 English texts of `ctl.common` and `ctl.cli` and 37 of `ctl.ui`, the German texts
"Deployment" 87 times. Examples: **Create deployment** (the button of the list and the button of the plan, the title
of the form), **Deployments** (the title of the list), "Looking for deployments at the providers…", "Password of the
deployment: ", "The deployment %{name} is up-to-date.", the hints of the DNS mode ("The deployment manages …").
The texts of the apps say "SAK instance" already; the only other "deployment" is the "deployment target" of Hugo in
static hosting, a term of another tool, which stays.

The verb stays: the command `sakctl deploy`, **Deploy changes**, **Force deploy**, "a deploy".

Fix, when the glossary changes: every noun "deployment" of `ctl.*` becomes "SAK instance" ("SAK-Instanz"), e.g.
**Create SAK instance**, **SAK instances**, "Password of the SAK instance: "; the code keeps its names
(`DeploymentManager`). Then the documentation can quote the labels without an exception.
