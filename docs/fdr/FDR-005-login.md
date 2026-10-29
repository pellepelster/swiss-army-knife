# FDR-005: Login

**Status:** Active
**Last reviewed:** 2026-10-29

## Overview

The apps of a deployment need a session. A user signs in with the admin password, or with a link by e-mail. The
admin password is the password of the deployment, so a deployment without a mail server has one user: the admin.

## Behavior

- **Password login.** The admin signs in with the password of the deployment. Only the admin can sign in with a
  password.
- **E-mail login.** With a mail server, every allowed address can sign in with a link by e-mail. Without a mail
  server there is no e-mail login.
- **The login page** shows what the deployment offers:
  - with a mail server: the e-mail login, and a link "Sign in with password";
  - without a mail server: the password login only.
- **One admin.** The admin is the first allowed address. With the password or by e-mail, it is the same user with
  the same data.
- **Limits.** A client tries only a few passwords an hour, and the server only a few more altogether. A wrong
  password tells nothing but that it is wrong.
- **sakctl** asks for the address of the admin and, optionally, for the mail server. The hint of the mail server
  says that without it only the admin can sign in, with the password.
- **The page shows who is signed in,** as the backend takes the session (`GET /api/v1/auth/who-am-i`).

## Design Decisions

### 1. The admin password is the deployment password

**Decision:** The admin signs in with the password of the deployment; there is no separate admin password.
**Why:** One password to remember for one deployment. sakctl derives everything else from it already.
**Tradeoff:** A login sends the deployment password to the server. A compromised server or a phished login gives
away the password, which is also the key to the ssh access and the cloud resources of the deployment.

### 2. Only a hash reaches the server

**Decision:** sakctl writes an argon2id hash of the password (its own salt, derived from the sak name) into
`backend.env`; the backend checks a password against it.
**Why:** The password is nowhere on the server. The salt of its own keeps the hash apart from the password hash
label of the cloud resources. The salt is derived, so every deploy writes the same hash and the backend does not
restart for nothing.
**Tradeoff:** The hash is in `backend.env` on the server; with it, the password can be guessed offline, as with the
password hash label.

### 3. The limits come before the hash

**Decision:** The limits of a client and of the server are checked before the password is hashed.
**Why:** argon2id is slow on purpose. Without the limits first, a flood of logins would keep the server busy.
**Tradeoff:** Someone can use up the attempts of an hour, and the admin cannot sign in with the password until the
hour is over. The e-mail login stays.

### 4. The mail server is optional

**Decision:** A deployment works without a mail server: the admin signs in with the password.
**Why:** A family or a small group may have no mail server of its own (refer to the README, "Target audience").
**Tradeoff:** Without a mail server there is only one user.

## Gates

- `Role.ADMIN`: the password login gives a session of the admin, the first of `auth.admins`.

## Related

- **ADRs:** none
- **FDRs:** FDR-001 (the hosts of the login page)

## Open Questions

- How does a password rotation reach the admin password? It is the deployment password, so a rotation changes
  both.
