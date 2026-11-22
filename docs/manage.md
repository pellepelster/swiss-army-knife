---
title: "Look After Your SAK Instance"
linkTitle: "Look after it"
description: "Update, change, back up, restore, maintain and destroy a SAK instance with sakadmin, in the UI and on the command line."
weight: 7
---

# Look after your SAK instance

After the [first steps](first-steps.md), your SAK instance runs on its own. You need sakadmin again to update it,
change its settings, back it up, maintain its server or destroy it.

In the UI, select the SAK instance in the list **SAK instances** and enter its password. Its page has the buttons
this page names. On the command line, each command takes the name of the SAK instance; it reads the token from
`HCLOUD_TOKEN` and the password from `SAK_PASSWORD`, or asks for them. `sakadmin --help` and
`sakadmin <command> --help` list all commands and options. On Windows the command line is `sakadmin-cli.exe`.

## Update

1. Update sakadmin: **Check for updates** in the footer of the UI, or `sakadmin update`
   ([Installation](installation.md#update)).
2. Deploy the SAK instance, as below. The deploy brings it to the version of sakadmin.

## Deploy

A deploy compares the SAK instance with what it must be and changes only what differs. The data volume and the
addresses always stay.

- **UI:** click **Check**. sakadmin shows which resources have changes. Read the plan, then click **Deploy changes**.
- **Command line:** `sakadmin deploy <name>`. sakadmin shows the plan. When the plan deletes or replaces a resource,
  it asks you to type the name of the SAK instance first.

`sakadmin list` shows all SAK instances of the project and the state of their resources.

## Change the settings

A deploy keeps the settings of the SAK instance unless you give other ones.

- **UI:** click **Config…**, change the settings, then deploy.
- **Command line:** give the setting with the deploy, for example `sakadmin deploy <name> --user-mode multi
  --smtp-host mail.example.org` ([Multiple users](users.md)). `sakadmin config show <name>` shows the settings of the
  running SAK instance.

The name, the password and the root domain of a SAK instance can not be changed.

## Back up and restore

A backup is one file with all data of the SAK instance, encrypted with a key from its password. Backup and restore
are on the command line only.

- **Back up:** `sakadmin backup <name>` writes `~/.sak/<name>/backups/<name>-<time>.age.sak` on your computer. Keep a
  copy of the file in another place.
- **Restore:** `sakadmin restore <name> <file>` replaces all data of the SAK instance with the backup. The SAK
  instance must exist: after the loss of a server, create it again with the same name and password, then restore.
  sakadmin asks before it replaces the data. The apps are not available while the restore runs.

A backup needs the password it was made with. Without the password, nobody can read it.

## Maintain the server

The server installs security updates on its own. sakadmin shows what is pending and does the rest.

- **UI:** click **Maintenance**, then **Status**, **Update**, **Restart services** or **Reboot**.
- **Command line:**
  - `sakadmin maintenance status <name>`: whether a reboot is pending, and what was upgraded since the last start.
  - `sakadmin maintenance update <name>`: upgrades all packages of the server. With `--reboot-server`, it reboots
    the server afterwards when a reboot is pending.
  - `sakadmin maintenance restart-services <name>`: restarts the services that still run old libraries.
  - `sakadmin maintenance reboot <name>`: stops the SAK instance, reboots the server and waits until the SAK instance
    answers again. It waits for a backup that runs.

## Destroy

A destroy deletes the server, its addresses, the firewall, the SSH key and the DNS records. It keeps the data volume
unless you ask to delete it too. Without the data volume, all data is gone: make a backup first.

- **UI:** click **Destroy** and enter the password. Tick the box to delete the data volume too. Read the plan, then
  confirm.
- **Command line:** `sakadmin destroy <name>`, with `--delete-data` to delete the data volume too.

## Get help on the server

`sakadmin ssh <name>` opens a session as root on the server, and `sakadmin ssh <name> -- <command>` runs one command.
You need this only when something is wrong.
