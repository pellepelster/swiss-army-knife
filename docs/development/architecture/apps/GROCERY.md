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
- `GROCERY-11` **Who learns.** Adding an item teaches the learned items (the suggestions) of the user who adds it,
  and, on a shared list, the list too; so does a photo attached to an item. What a user learns is theirs alone,
  never shown to another user. What a shared list learns is seen by every user who sees the list: its suggestions
  on that list are the list's and their own, one of a name, the one used more; every one of them can forget one of
  the list's. When the list is unshared, what it learned stays with it: its owner keeps it, the others no longer
  see it, as they no longer see the list; shared again, it is back for everyone. A deleted list takes it along.
- `GROCERY-12` **A photo of an item.** An item has at most one photo, attached and removed from the menu of its row
  (on Android from the camera or the photos of the phone). The row shows it scaled, with the same space to its left,
  above and below it; a tap on it shows it in full and does not check the item. Everyone who sees the list sees it.
  It goes onto the learned item of the user who attached it and, on a shared list, onto the list's (`GROCERY-11`):
  the next item of its name gets it, on a shared list the list's photo first. The photos stay with what a list
  learned when it is unshared. A photo is fetched in the size it shows at: the smallest of the sizes the backend
  scales to that has at least the pixels of the screen there, the photo itself when none has.

