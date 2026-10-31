---
title: "Grocery"
description: "The app of grocery lists: items with a quantity and a category, shared lists and suggestions of what was bought before."
weight: 35
---

# Grocery

Adds to [the architecture of the apps](INDEX.md) and [SYSTEM.md](../SYSTEM.md).

## Purpose

Grocery lists: what to buy, how much and in which category, ticked off while shopping. A list can be shared with all
users; what a user added before is suggested again.

## General

- `GROCERY-1` **Lists.** Grocery is a list-based app (refer to [List-based apps](INDEX.md#list-based-apps)): every
  item is on one list. A user sees their own lists, the default one first, and the shared lists of the others; only
  the owner of a list renames, shares or deletes it. Deleting a list deletes its items.

## Data

- `GROCERY-2` **Bought, not gone.** Checking is how an item leaves a list; the page has no delete for an item. A
  checked item is stored with when and by whom it was checked first, but a list no longer shows it; while the check
  can still be undone, a few seconds, the item stays on the list struck through.
- `GROCERY-3` **Quantity.** An item can have an amount, a positive number with up to 3 decimal places, and with it a
  unit, which is free text. The app does not convert units or add amounts up.
- `GROCERY-4` **Order.** A list shows the items to buy, by the order of their category, the ones without a category
  last, each group in the order the items were added.
- `GROCERY-5` **Shared lists.** Every user adds, checks and removes the items of a shared list (`LIST-2`); each item
  keeps the user who added it as its owner.
- `GROCERY-6` **Categories.** An item has one of the fixed categories of the code, or none: fruit & vegetables,
  bakery, dairy & eggs, meat & fish, pantry, frozen, drinks, household, other.

## API

- `GROCERY-9` Read, create, rename, share and delete the lists; read the items of a list; add, change, check and
  remove an item, a new one without a list goes to the default list; remove the bought items of a list; read the
  categories.

## Suggestions

- `GROCERY-7` **Learned suggestions.** Every item a user adds teaches their own catalogue: its name in any case, the
  category and the quantity used last, how often and when last. Suggestions come only from that catalogue, the
  items to buy on the list left out, the ones bought most and last first.
- `GROCERY-8` **Forget a suggestion.** A user can delete a product from their catalogue; no list changes, and it is
  learned again the next time an item of the name is added.

## External systems

None.
