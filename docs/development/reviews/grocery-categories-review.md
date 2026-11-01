---
title: "Review: Grocery categories"
linkTitle: "Grocery categories"
description: "The review of the categories of a grocery list (GROCERY-13): backend, model, web ui and Android app."
weight: 3
---

# Review: Grocery categories

**Created:** 2026-11-02

Scope: the categories of a grocery list (`GROCERY-13`) before their first commit: the migration
`0007-grocery-categories`, `GroceryRepository`, `GroceryService`, the routes below `/api/v1/grocery`, the language of
the sign-in in `LoginListener`, the list page and the settings of a list in the web ui, and the list screen, the
category screen and the settings screen of the Android app.

What is good: every write checks the access in its own SQL; a category of another list or a deleted one is refused
in the service and again in the repository; a list and its default categories are made in one transaction; a deleted
category goes from the items and the learned items, a deleted list takes its categories along; every change sends a
change event for its list (`LIST-4`); the migration runs on sqlite and on H2. The web ui and the app map an unknown
category to "No category", send every id once when they order the categories, and send `"categoryId":null`
explicitly. Delete is red and asks first (`UX-3`, `UX-4.1`); all texts have English and German.

Intended, no finding: the lists made before the migration have no categories, and their items and learned items
lose theirs (no backwards compatibility); only a new list gets the default categories.

## Must fix

### ~~`GC-1` Android: a move or a delete gives the typed name to another category~~

**Fixed:** each row is keyed by its category (`key(category.id)`), the names typed are drafts in the model, so they stay with their category when the rows move or load again.

`GroceryApp.kt` (`CategoriesSection`) draws the rows with `forEachIndexed` and without `key(category.id)`, so the
state of a row stays with its place, not with its category. A user types a new name in row 1 and taps its ↓ (an icon
button takes no focus): row 1 now shows category B, the typed name is gone and never saved, and the cursor is in the
field of B. A delete shifts the rows the same way.

Fix: wrap each row in `key(category.id) { … }`; a Compose test of a move while a name is typed.

### ~~`GC-2` Web: a deleted category can stay in the settings~~

**Fixed:** a deleted row goes by its id; the Delete of the row menu is disabled while a change of the categories is saved.

`grocery-list-form.ts` (`deleteCategory`) takes the row away by the identity of the object (`each !== category`),
not by its id. The Delete of the row menu is not disabled while another change runs. A user moves A, then deletes B
before the answer of the move comes: the answer of the move replaces all objects, the delete removes nothing, B stays
on the page, and every next move is refused with 400, because its ids name B.

Fix: filter by `each.id !== category.id`; disable the Delete of the menu while a change runs.

## Should fix

### ~~`GC-3` The settings never load the categories again~~

**Fixed:** both settings load the categories again after a refused change and on a change event of the list.

Neither the web settings nor the Android settings listen to the change events of the list, and an error only shows
its message. When the owner changes the categories on another device, or a change fails, the settings keep the old
categories: every ↑/↓ is refused with 400 ("The order must name every category of the list once."), a rename or a
delete of a category that is gone gives 404, until the page or screen is opened again.

Fix: load the categories again after a refused change and on a change event of the list, as the list page does.

### ~~`GC-4` Changes of the categories that come at the same time get lost~~

**Fixed:** the changes of the categories go into a queue (web: one after the other, Android: a `Mutex`); each one is made from the state it finds when its turn comes, the answer of a move sets only the order.

Web: one `busy` flag, but the name field, the emoji picker and the Delete of the menu do not check it; a late answer
of a move puts an old name back into a field, and a click on ↑/↓ right after a name field was left does nothing (the
rename disables the button before its click comes). Android: `categoriesRequest` returns without a word while a
change runs, so an emoji chosen or a name left during a move is not saved and no message says so.

Fix: queue the changes (e.g. `concatMap`, a channel) instead of dropping them; apply the answer of a move only to
the order.

### ~~`GC-5` Android: a name typed and not yet left is lost on back and on Save~~

**Fixed:** the names typed and not saved are saved when the settings close (back arrow, back of the system, Save); an invalid one is not.

A name is saved when its field loses the focus or on Done. Back (arrow or system) and Save take no focus and set
`settings = null` first, so the late save returns early: the rename is lost without a message.

Fix: save the typed names before the settings close, or keep them as drafts in the model.

### ~~`GC-6` Android: categories that could not be loaded look like none~~

**Fixed:** a failed load shows no rows and no add field, but its error and "Load again".

When loading the categories fails, the section says "The list has no categories yet." and adds a category to an
empty local list; the next move sends too few ids and is refused with 400.

Fix: a state for a failed load: no rows and no add field, a message and a retry.

### ~~`GC-7` Android: the error of a category shows far above it~~

**Fixed:** the errors of the categories show in their section, the error of a name under its field; a new name that is empty or longer than 32 characters shows its error, also on Done.

The errors of the categories use `settings.error`, shown above Delete and Save; the categories are below them, and
the form scrolls. A 409 ("The list has a category of the name.") at the end of a long list is not seen. A name of
more than 32 characters only disables Add, without an error under the field (`UX-6`).

Fix: an error of the categories in their section, the error of a name under its field.

### ~~`GC-8` A change of an item does not teach the list its category~~

**Fixed:** `updateItem` sets a changed category on the list's learned item of the name too.

`PUT /items/{id}` (`GroceryRepository.updateItem`) changes the category of the item, but not the one of the learned
item of its name; only `PUT /items/{id}/category` teaches it (`GROCERY-13`: "It goes onto the learned item of the name
of the list"). An API client that sets the category with the change of the item gets the old category on the next
item of the name.

Fix: when the category changes in `updateItem`, set it on the list's learned item of the name too.

### ~~`GC-9` The comment of the change of an item says the opposite of what it does~~

**Fixed:** the comment says that a missing `categoryId` takes the category away from the item.

`GroceryItemRequest.kt` says "a new item without one gets the one the list learned of its name"; it is the request
of a change, and a missing `categoryId` takes the category away from the item.

Fix: "none when missing: the item loses its category".

### ~~`GC-10` A deleted category changes items without `updated_at`~~

**Fixed:** both updates set `updated_at`.

`deleteCategory` sets `category_id` of the items and the learned items to null without `updated_at`, which
AGENT.md requires for a row that really changes.

Fix: set `updated_at` in both updates.

## Small items

- ~~`GC-11` `PUT /lists/{id}/categories/order` answers a plain array; every other answer of the categories is a page
  (`GET /lists/{id}/categories`). Answer a page, or 204.~~ **Fixed:** the order answers a page of all categories.
- ~~`GC-12` The names of the categories are unique with their case: "Dairy" and "dairy" are two categories of a list,
  the names of the items are compared without case (`name_key`). Decide; for no case, a unique index on a key.~~ **Fixed:** a category has a `name_key` (`groceryNameKey`), unique among the live categories of a list; "Dairy" and "dairy" are one name (409).
- ~~`GC-13` `setCategory` answers 404 ("no such item") instead of 400 ("unknown category") when the owner deletes the
  category between the check of the service and the write of the repository.~~ **Fixed:** `setCategory` answers 400 ("unknown category") when the item is still there.
- ~~`GC-14` Two creates at the same time can both pass the check of `MAX_CATEGORIES`; sqlite most likely refuses the
  second write (500). The same pattern is in other methods of the repository.~~ **Fixed:** the count and the place are checked in the statement that inserts the category.
- ~~`GC-15` Web: after a move, the focus goes to the body (the button that was clicked is disabled), so a keyboard
  user can not move a category more than one place without tabbing back.~~ **Fixed:** the arrows are disabled at the ends only; after a move the focus goes back to the arrow clicked, or to the other one at an end.
- ~~`GC-16` Web: a name typed with spaces around it that is the same name after the trim keeps its spaces in the
  field; "No icon" is disabled without a tooltip that says why (`UX-8`, `UX-12`).~~ **Fixed:** a name the same after the trim is put back without its spaces; "No icon" has a tooltip.
- ~~`GC-17` Web: a failed load of the categories shows no items of the list ("The items could not be loaded.").~~ **Fixed:** the items show under "No category" with the error "The categories could not be loaded."
- ~~`GC-18` Android: the category screen keeps the item it was opened for; an item bought by another user meanwhile
  gives 404 when a category is chosen, and a choice equal to the old category of the item sends nothing.~~ **Fixed:** the category screen takes the item of each load, an item that is gone leads back to the list.
- ~~`GC-19` Android: the category screen and the categories of the settings can not be pulled down to reload
  (`ANDROID-3`); the back arrow of the category screen and the ⋮ of a category row have no content description.~~ **Fixed:** pull to reload on the category screen and on the settings; the back arrow and the ⋮ of a row have a content description.
- ~~`GC-20` Tests that are missing: the change events of the categories (create, rename, delete, order,
  `setCategory`); `PUT /items/{id}` without `categoryId`; a deleted category taken from a learned item; the errors of
  an order or a delete in the web ui; a failed load of the categories and a 401 during a change of a category on
  Android; changes that come at the same time (`GC-2`, `GC-4`).~~ **Fixed:** the tests are added; the move of a row with a name typed is tested in the model (the app has no Compose tests).
