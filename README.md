# SwissArmyKnife

SwissArmyKnife is a self-hosted, privacy first micro-cloud offering various productivity apps. It is for families and
small groups: a few people who know and trust each other.

## Feature Overview

- **Privacy first** Nothing sends data to a third party unless you activate it explicitly and agree to it first. The
  apps load nothing from outside, and your data stays on your own server.
- **Install, update and maintain with one tool** `sakctl` creates a deployment, updates it and deletes it, in a
  window or on the command line.
- **Backup** `sakctl backup` copies all data of a deployment to one encrypted file on your computer.
- **Restore** `sakctl restore` restores the data back into a running deployment.
- **On every device** All apps work on a phone as well as on a desktop. On a phone, you can install them as an app (PWA)
  on the home screen.
- **No complex permissions** Data is either private to the user who made it, or shared with all users of the
  deployment. There are no roles per item, no groups and no sharing with single users.
- **Single user mode** One user, the admin, signs in with the password of the deployment. No mail server is needed.
- **Multi user mode** Users sign in with a link by e-mail; the admin can also sign in with the password of the
  deployment. It needs a mail server and the e-mail address of the admin as well as all other users.
