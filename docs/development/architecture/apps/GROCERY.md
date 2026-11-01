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
  - `GROCERY-11` **A list learns.** Adding an item to a list teaches the learned items (the suggestions) of that list;
    so does a photo attached to an item. Every learned item belongs to one list: only that list suggests it, never
    another list, also not another list of the same user. A user learns nothing of their own. Every user who sees the
    list sees what it learned and can forget a learned item. When the list is unshared, what it learned stays with
    it: its owner keeps it, the others no longer see it, as they no longer see the list; shared again, it is back for
    everyone. A deleted list takes it along.
- `GROCERY-12` **A photo of an item.** An item has at most one photo, attached and removed from the menu of its row
  (on Android from the camera or the photos of the phone). The row shows it scaled, with the same space to its left,
  above and below it; a tap on it shows it in full and does not check the item. Everyone who sees the list sees it.
  It goes onto the learned item of its name of the list (`GROCERY-11`): the next item of its name on that list gets
  it, no item on another list. The photos stay with what a list learned when it is unshared. A photo is fetched in
  the size it shows at: the smallest of the sizes the backend scales to that has at least the pixels of the screen
  there, the photo itself when none has.
- `GROCERY-13` **The categories of a list.** Every list has its own categories, each with a name (unique on the list,
  in any case), an icon (one emoji or none) and a place in their order; a list shows its items in groups by category in that order, the items without a
  category last. A new list starts with the default categories (fruit & vegetables, bakery, dairy & eggs, meat & fish,
  pantry, frozen, drinks, household, other, each with an icon), named in the language of the request that makes it,
  for the list of the sign-in that of the sign-in. Only the owner of the list adds, renames, orders and deletes them,
  in the settings of the list; every user who sees the list sees them. An item has one category of its list or none,
  set from the menu of its row by every user who sees the list (on Android on a screen of its own, which goes back to
  the list once one is chosen). It goes onto the learned item of the name of the list (`GROCERY-11`): the next item of
  the name on that list gets it. A category never applies to another list. A deleted category goes from its items and
  the learned items; a deleted list takes its categories along.

