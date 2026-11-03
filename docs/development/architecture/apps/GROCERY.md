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
- `GROCERY-15` **Quantity.** The menu of a row sets the quantity of an item: a number, typed with a point or a comma,
  and a unit chosen from a list of the usual ones in the language of the user (g, kg, ml, l, EL, TL, Prise, Packung,
  Dose, Bund, Stück), or none. A unit the item has that is not in the list stays one to choose. No number takes the
  unit away too. The web shows the fields in the row, Enter or Save saves, Escape or Cancel keeps the quantity; Android
  shows them in a dialog, Save saves, Cancel keeps it.
- `GROCERY-16` **Items from a photo.** With the AI features turned on (the setting of the grocery lists, `APP-PRIVACY-1`)
  a robot next to the field of a new item takes a photo or lets the user choose one, of a recipe or a shopping list.
  Its label names OpenRouter, where the photo goes (`PRIVACY-2`). The AI reads the items in it, with their quantity
  (`GROCERY-15`); nothing is added yet. The user sees the items in a list first, corrects their names and
  quantities, leaves out the ones not to buy and adds the others to the list at once, or cancels. The photo stays in the
  file storage of the user (`SHARED-3`), it is attached to no item.

## Data

The lists, their categories and their items (`grocery_lists`, `grocery_categories`, `grocery_items`). The photo of an
item is a file of the file storage (`SHARED-3`).

## API

Read, create, rename, share and delete a list; read the items to buy of a list, its suggestions and its categories;
add, change, order and delete a category; forget a suggestion; add, change, check and delete an item, set its
category, attach and take away its photo, read the photo in a scaled size (`GROCERY-12`); listen to the changes of the
lists (`GET /api/v1/grocery/changes`, `LIST-4`). Every user may ask whether the items in a photo can be discovered
now (`GET /api/v1/grocery/settings`, `discoverItems`) and discover them in a photo of the file storage
(`POST /api/v1/grocery/discover/{id}`, `GROCERY-16`); the photos and the texts discovered share a limit for each user.

## Settings

- `ai` (scope `grocery`): the AI features of the grocery lists (`GROCERY-16`), off by default; it needs the AI of the
  deployment turned on (scope `ai`).

## External systems

- **OpenRouter**, only with the AI features turned on and only when the user takes or chooses a photo with the robot
  (`GROCERY-16`): the photo goes to the vision model of the deployment, through providers that do not collect the data.
  OpenRouter sees the photo and the API key of the deployment, not who took it.
