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
- `GROCERY-3` **Check an item.** A tap checks an item: it is struck through with a red line and checked according to
  `UX-4.2`; undo, or a second tap, keeps the item. A checked item is bought: it leaves the list and
  stays as its history (`GROCERY-14`). Delete, in the menu of a row (`UX-12`), takes an item away for good.
  - `GROCERY-11` **A list learns.** A list suggests the names of its items, one for each name, with the quantity,
    category and photo of the latest item of the name; never on another list, a user learns nothing of their own. A
    name to buy is not suggested. Forgetting a suggestion deletes the bought items of its name.
- `GROCERY-12` **A photo of an item.** An item has at most one photo, attached and removed from the menu of its row
  (on Android from the camera or the photos of the phone). The row shows it scaled, with the same space to its left,
  above and below it; a tap on it shows it in full and does not check the item. Everyone who sees the list sees it.
  The next item of its name on that list gets the photo of the latest item of the name (`GROCERY-11`), no item on
  another list. A photo is fetched in the size it shows at: the smallest of the sizes the backend scales to that has
  at least the pixels of the screen there, the photo itself when none has.
- `GROCERY-13` **Categories.** Every list has its own categories (a name unique on the list, an icon, an order) and
  shows its items in groups in that order, the ones without a category last. A new list gets the default categories
  in the language of the request. Only the owner of the list changes them, in its settings; every user who sees the
  list sets the category of an item, from the menu of its row.
- `GROCERY-14` **Bought items.** An eye in the toolbar of a list shows the bought items too: `eye-slash` while
  hidden, `eye` while shown. The latest bought item of each name that is not to buy shows in its category after the
  items to buy, struck through with a red line; a tap does not check it again, the menu of its row changes it like
  an item to buy (e.g. to fix its name).

