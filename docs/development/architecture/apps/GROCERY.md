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
  item is on one list. A user sees their own lists and the shared lists of the others, by name; only the owner of a
  list renames, shares or deletes it, but not their last own list (`LIST-1`). Deleting a list deletes its items.

## Data

- `GROCERY-2` **Bought is deleted.** Checking an item deletes it; the page has no other delete for an item. While the
  check can still be undone, a few seconds, the item stays on the list struck through.
- `GROCERY-3` **Quantity.** An item can have an amount, a positive number with up to 3 decimal places, and with it a
  unit, which is free text. The app does not convert units or add amounts up.
- `GROCERY-4` **Order.** A list shows the items to buy, by the order of their category, the ones without a category
  last, each group in the order the items were added.
- `GROCERY-5` **Shared lists.** Every user adds, changes and checks the items of a shared list (`LIST-2`); each item
  keeps the user who added it as its owner.
- `GROCERY-6` **Categories.** An item has one of the fixed categories of the code, or none: fruit & vegetables,
  bakery, dairy & eggs, meat & fish, pantry, frozen, drinks, household, other.

## API

- `GROCERY-9` Read, create, rename, share and delete the lists; read the items to buy of a list; add an item to a
  list and change it; check an item, with a delete (`GROCERY-2`); read the categories.

## Suggestions

- `GROCERY-7` **Learned suggestions.** Every item a user adds teaches their own catalogue: its name, the same in any
  case, Unicode form and spacing, but not without its accents, the category and the quantity used last, how often and
  when last. Suggestions come only from that catalogue, the items to buy on the list left out, the ones bought most and
  last first.
- `GROCERY-8` **Forget a suggestion.** A user can delete a product from their catalogue; no list changes, and it is
  learned again the next time an item of the name is added.

## Android app

`sak-grocery-android` (package `io.pelle.sak.grocery`) shows the grocery lists on a phone. It is a client of the
api like the web ui, a gradle build of its own (`mise run grocery:android:run` starts it in an emulator).

- `GROCERY-10` **Read only.** The app shows the lists and their items to buy, by category as `GROCERY-4` orders them;
  it does not add, change or check items yet.
- `GROCERY-11` **One deployment.** The user enters the base domain, the app uses the api at
  `https://api.<base domain>/v1` (`SERVER-5`) and checks it with `GET /info`. Only the debug build takes a whole url,
  e.g. `http://10.0.2.2:8080/api/v1` for the backend of `local:run`, and only it sends http.
- `GROCERY-12` **Sign-in by password or by link.** The app offers what `GET /auth/login/methods` tells. A link it asks
  for (`client: android`) is handed on to the app by the signin page of the web app (refer to
  [FDR-005](../../fdr/FDR-005-login.md)); the app takes the token only for a login it asked for on the same deployment
  within 15 minutes, so nobody signs the user in with a link of their own.
- `GROCERY-13` **The session stays on the phone.** The session token is encrypted with a key of the Android keystore,
  the app has no backup; a 401 or the end of the session leads to the sign-in, the deployment stays.

## External systems

None. The Android app contacts only the deployment.
