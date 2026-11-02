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
- `GROCERY-2` **Add an item.** A list has an input field at its top. The user types the name of an item there and
  adds it to the list; the field suggests the names of the list (`GROCERY-11`).
- `GROCERY-3` **Check an item.** A tap checks an item: it is struck through with a red line and checked according to
  `UX-4.2`; undo, or a second tap, keeps the item. A checked item is bought: it leaves the list and stays as its history
  (`GROCERY-14`). Delete, in the menu of a row (`UX-12`), takes an item away for good.
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
  in the language of the request. Every user who sees the list changes them, in its settings (`LIST-2`), and sets the
  category of an item, from the menu of its row.
- `GROCERY-14` **Bought items.** An eye in the toolbar of a list shows the bought items too: `eye-slash` while
  hidden, `eye` while shown. The latest bought item of each name that is not to buy shows in its category after the
  items to buy, struck through with a red line; a tap does not check it again, the menu of its row changes it like
  an item to buy (e.g. to fix its name).

## Data

The lists, their categories and their items (`grocery_lists`, `grocery_categories`, `grocery_items`). The photo of an
item is a file of the file storage (`SHARED-3`).

## API

Read, create, rename, share and delete a list; read the items to buy of a list, its suggestions and its categories;
add, change, order and delete a category; forget a suggestion; add, change, check and delete an item, set its
category, attach and take away its photo, read the photo in a scaled size (`GROCERY-12`); listen to the changes of the
lists (`GET /api/v1/grocery/changes`, `LIST-4`).

## Settings

None.

## External systems

None.
