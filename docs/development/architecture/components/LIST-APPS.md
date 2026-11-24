---
title: "List apps"
description: "The rules all list apps share: the first list, shared lists, the lists first and changes that reach every client."
weight: 35
---

# List apps

Adds to [the architecture of the components](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

An app whose items belong to lists of the user keeps to these rules; the notes, the grocery lists and the todo lists
are such apps.

- `LIST-1` **A first list.** When a user signs in who never had a list of their own, each list-based app makes them a
  private list named `My <entity>` in their language, e.g. "My Todos"; the shared lists of others do not count. The
  owner of a list renames, shares and deletes it, the last one too; a deleted list does not come back. A new item
  always names its list; no request makes a list on the side.
- `LIST-2` **Shared lists.** A list has a shared flag; a shared list is shared with all other users, who can add items
  to it and delete items from it, and change its icon and its categories. Only its owner renames, shares or unshares it
  and deletes it. The owner of an item is who added it (`MODEL-5`), but the access follows the list (`MODEL-6`): every
  user can delete an item of a shared list, also one another user created.
- `LIST-3` **The lists first.** A list-based app, in the web app and on Android, opens on the overview of all lists of
  the user: their own lists and the shared lists of the others. From there the user opens a list; a list has a back
  action that leads to the overview. An Android app remembers the list that was open when it was left and opens it
  again; when the user went back to the overview, or the list is gone, it opens on the overview. A sign-out forgets
  the list.
- `LIST-4` **Changes reach every client.** A change of a list or of its items, its deletion included, reaches every
  open client of each user who sees the list, in the web app and on Android alike: the backend sends a server-sent
  event with the id of the list and nothing of its data, and the client loads the list again. A client listens only
  while it is in the foreground; when it comes back, it loads again what it shows, so it sees what changed meanwhile.
