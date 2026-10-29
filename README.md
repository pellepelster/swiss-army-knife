# SwissArmyKnife

SwissArmyKnife is a self-hosted, privacy first micro-cloud offering various productivity apps.

## Features

- **Install, update and maintain with one tool.** `sakctl` creates a deployment, updates it and removes it, in a
  window or on the command line. It also opens an ssh session on the server. You keep only the name and the password
  of the deployment; sakctl derives all keys from them.
- **Backup and restore.** `sakctl backup` copies all data of a deployment to one file on your computer. The server
  encrypts the data before it leaves the server. `sakctl restore` puts the data back, also into a deployment that you
  created again after a loss.
- **On every device.** All apps work on a phone as well as on a desktop. On a phone, you can install them as an app
  (PWA) on the home screen.

## Target audience

SwissArmyKnife is for families and small groups: a few people who know and trust each other.

- **No complex permissions.** Data is either private to the user who made it, or shared with all users of the
  deployment. There are no roles per item, no groups and no sharing with single users.

## Deployment modes

A deployment is created in one of two modes

- **Single user.** One user, the admin, signs in with the password of the deployment. No mail server is needed.
- **Multi user.** Users sign in with a link by e-mail; the admin can also sign in with the password of the
  deployment. It needs a mail server and the e-mail address of the admin as well as all other users.
