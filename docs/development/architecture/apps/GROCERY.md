---
title: "Grocery"
description: "An app to manage grocery lists."
weight: 40
---

# Grocery

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

An app to manage grocery lists.

## General

- `GROCERY-1` **Lists.** Grocery is a list based app (refer to [List-based apps](INDEX.md#list-based-apps)).
- `GROCERY-2` **Add Item.** Lists have an input field ad the top, that allows to quickly enter items into the list
- `GROCERY-3` **Check an item.** A tap checks an item: it is struck through and deleted according to `UX-4.2`, after
  1.5 seconds; undo, or a second tap, keeps the item.
- `GROCERY-11` **Teaching stays with the user.** What a user does teaches only their own learned items (the
  suggestions): adding an item, and anything else that teaches, such as a photo attached to an item. It never teaches
  another user, also not on a shared list: what one user adds to it changes no one else's suggestions.
- `GROCERY-12` **A photo of an item.** An item has at most one photo, attached and removed from the menu of its row
  (on Android from the camera or the photos of the phone). The row shows it scaled, with the same space to its left,
  above and below it; a tap on it shows it in full and does not check the item. Everyone who sees the list sees it.
  It goes onto the learned item of the user who attached it and comes back on their next item of its name
  (`GROCERY-11`). A photo is fetched in the size it shows at: the smallest of the sizes the backend scales to that has
  at least the pixels of the screen there, the photo itself when none has.

