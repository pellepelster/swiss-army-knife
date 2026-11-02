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
  `UX-4.2`, after 1.5 seconds; undo, or a second tap, keeps the item. A checked item is bought: the list no longer
  shows it, it stays as the history of the list (`GROCERY-11`, `GROCERY-14`). The Delete of the menu of its row
  (`UX-12`) takes an item to buy away for good, e.g. one added by mistake.
  - `GROCERY-11` **A list learns.** The suggestions of a list are the names of the items added to it before, to buy
    or bought, one for each name, with the values (quantity, category, photo) of the latest item of the name; a name to
    buy is not suggested. Only that list suggests them, never another list, also not another list of the same user. A
    user learns nothing of their own. Every user who sees the list sees its suggestions and can forget one: the bought
    items of its name go for good, an item to buy stays, the name comes back once it is added again. When the list is
    unshared, its items stay with it: its owner keeps the suggestions, the others no longer see them, as they no longer
    see the list; shared again, they are back for everyone. A deleted list takes them along.
- `GROCERY-12` **A photo of an item.** An item has at most one photo, attached and removed from the menu of its row
  (on Android from the camera or the photos of the phone). The row shows it scaled, with the same space to its left,
  above and below it; a tap on it shows it in full and does not check the item. Everyone who sees the list sees it.
  The next item of its name on that list gets the photo of the latest item of the name (`GROCERY-11`), no item on
  another list. A photo is fetched in
  the size it shows at: the smallest of the sizes the backend scales to that has at least the pixels of the screen
  there, the photo itself when none has.
- `GROCERY-13` **The categories of a list.** Every list has its own categories, each with a name (unique on the list,
  in any case), an icon (one emoji or none) and a place in their order; a list shows its items in groups by category in
  that order, the items without a category last. A new list starts with the default categories (fruit & vegetables, bakery, dairy & eggs, meat & fish,
  pantry, frozen, drinks, household, other, each with an icon), named in the language of the request that makes it,
  for the list of the sign-in that of the sign-in. Only the owner of the list adds, renames, orders and deletes them,
  in the settings of the list; every user who sees the list sees them. An item has one category of its list or none,
  set from the menu of its row by every user who sees the list (on Android on a screen of its own, which goes back to
  the list once one is chosen). The next item of the name on that list without a category gets the one of the latest
  item of the name (`GROCERY-11`). A category never applies to another list. A deleted category goes from all items of
  the list, the bought ones too; a deleted list takes its categories along.
- `GROCERY-14` **The bought items.** The toolbar of a list has a button with an eye: crossed out (Font Awesome
  `eye-slash`) while the list shows only the items to buy, open (`eye`) while it shows the bought items too. The
  bought items show in their category after the items to buy, the last bought first, struck through with a red line;
  a tap does not check them again and the menu of their row is disabled.

