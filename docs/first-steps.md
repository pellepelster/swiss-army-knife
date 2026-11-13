---
title: "First Steps"
description: "Choose the DNS mode and the user mode, then create your first SAK instance for a single user with the sakctl UI."
weight: 6
---

# First steps

After you [install sakctl](installation.md), choose the DNS mode and the user mode, then create your first SAK
instance for a single user.

## The DNS mode

A SAK instance uses a **root domain**, for example `example.org`: a DNS zone in your Hetzner Cloud project. SAK does
not create the zone; create it in the Hetzner Cloud Console first. The **DNS mode** sets which DNS records the SAK
instance manages and its SAK address:

| DNS mode                  | DNS records that the SAK instance manages        | SAK address, for example `family` and `example.org` |
|---------------------------|--------------------------------------------------|-----------------------------------------------------|
| **Subdomain** (default)   | `<name>.<root domain>`, `*.<name>.<root domain>` | `https://sak.family.example.org`                    |
| **Domain**                | `<root domain>`, `*.<root domain>`               | `https://sak.example.org`                           |

- Use **Subdomain** in most cases. More SAK instances can use the same root domain, and the records of the root
  domain do not change.
- Use **Domain** only for a root domain without other use. The SAK instance replaces the address records of the root
  domain, so a website at `example.org`, for example, stops working. No other SAK instance can use it.

## The user mode

- **Single user:** one user, the admin, who signs in with the password of the SAK instance. No mail server needed.
- **Multiple users:** the admin adds users by their e-mail address. Users sign in with a link sent by e-mail, the
  admin also with the password. You need a mail server (SMTP) that sends the links.

Start with **Single user** if you use SAK alone or want to try it.

## Create a SAK instance for a single user

You need:

- sakctl and Docker or Podman ([Installation](installation.md));
- the API token of your Hetzner Cloud project, with read and write access;
- the DNS zone of your root domain in the same project.

1. **Start sakctl.** It finds Docker or Podman and downloads the image of its tools. The first time, this takes a few
   minutes.

   ![sakctl finds the container runtime and downloads its tools](screenshots/sakctl-ui-checks.png)

2. **Add the API token.** Click **Add token**, select **Hetzner Cloud**, paste the token and click **Add token**.
   sakctl checks the token by creating and deleting an SSH key in the project. It keeps the token only while it runs.

   ![The API token of Hetzner Cloud](screenshots/sakctl-ui-token.png)

3. **Go on.** The token is in the list **Provider tokens**. Click **Next**.

   ![The provider tokens with the token of Hetzner Cloud](screenshots/sakctl-ui-providers-token.png)

4. **Start the creation.** The list **SAK instances** is empty. Click **Create SAK instance**.

   ![No SAK instance yet](screenshots/sakctl-ui-deployments-empty.png)

5. **Fill in the form.**
   - **Name:** lower case letters, digits and dashes, for example `family`. In the DNS mode **Subdomain**, it is part
     of the SAK address.
   - **Password** and **Repeat the password:** at least 16 characters. You sign in to the apps with it, and sakctl
     needs it with the name to manage the SAK instance. Keep it safe: nobody can recover a lost password, and the
     backups need it too.
   - **Location:** where the server is.
   - **Root domain:** the DNS zone of your project.
   - **DNS mode:** **Subdomain** ([the DNS mode](#the-dns-mode)).
   - **User mode:** **Single user**. Leave **Admin e-mail address (optional)** empty.

   ![The form of a new SAK instance: name, password, location and root domain](screenshots/sakctl-ui-create.png)

   ![The form of a new SAK instance: the DNS mode Subdomain and a single user](screenshots/sakctl-ui-create-users.png)

   Then click **Plan**.

6. **Read the plan.** It shows each resource that sakctl creates: the SSH key, the data volume, the addresses, the
   firewall, the server, the DNS records and the provisioning of the server. Nothing has changed yet. Click **Create
   SAK instance**.

   ![The plan of a new SAK instance](screenshots/sakctl-ui-plan.png)

7. **Wait.** sakctl creates the resources and sets up the server, in a few minutes.

   ![sakctl creates the SAK instance](screenshots/sakctl-ui-provisioning.png)

8. **Done.** When all resources are created, click **Done**. The page of the SAK instance shows its configuration and
   resources.

   ![The SAK instance is created](screenshots/sakctl-ui-done.png)

9. **Sign in.** Open the SAK address in a browser, for example `https://sak.family.example.org`, enter the password
   in **Admin password** and click **Sign in**.

   ![Sign in with the password](screenshots/web-login-password-desktop.png)

Your SAK instance now runs on its own. You need sakctl again only to change, update, back up or maintain it.

Next: read [what the apps can do](features.md), or install the apps on your phone:
[the web apps](apps/android/web-apps.md) or [the native Android apps](apps/android/native-apps.md).
