---
title: "Review: Grocery bought items"
linkTitle: "Grocery bought items"
description: "The review of the bought items of a grocery list (GROCERY-3, GROCERY-11, GROCERY-14) and of the condensed data model."
weight: 4
---

# Review: Grocery bought items

**Created:** 2026-11-02

Scope: everything since commit `b003320`: the data model condensed into `0001-initial.yaml` (no `grocery_products`,
`grocery_items.checked_at`), `GroceryRepository`, `GroceryService`, the routes below `/api/v1/grocery`, and the list
page of the web ui and the list screen of the Android app with the eye of the bought items, the Delete and the Rename
of an item.

What is good: the condensed changeset builds every table, column, index and constraint the seven changesets before
built, in an order H2 takes; `SchemaTest` passes. The suggestion query is right: one row for each name, the latest
item by id, no ties, the names to buy left out, a fixed order. Every change of an item, to buy or bought, checks the
access in its SQL and sends a change event (`LIST-4`); only a check needs an item to buy. Both apps keep the eye
through every reload, send `"categoryId":null` explicitly, ask before a Delete (`UX-4.1`) and show a red 2 px
strike-through. The tests cover the check, the undo, the Delete, the eye and the Rename with its errors.

## Must fix

### ~~`GB-1` A Delete in the undo window of an item ends in an error~~

**Fixed:** a Delete of the item that waits drops its check and its toast on the web and on Android; a Rename of it changes what the check puts back.

Web (`grocery-page.ts`, `deleteItem`) and Android (`GroceryModel.deleteItem`) do not cancel the check that waits for
the end of the undo window (`GROCERY-3`). A user taps Milk, then deletes it from its menu within 1.5 seconds: the
DELETE goes out, then the check (`POST /items/{id}/check`) is refused with 404, the list shows "The item could not be
checked.", and on the web the toast "Milk bought / Undo" stays for an item that is gone.

Fix: a Delete of the item that waits drops its check (and its toast) first; a Rename of it changes what the check
puts back.

### ~~`GB-2` Android: the eye-slash is cut off~~

**Fixed:** both icons have the paths of `svgs-full/solid/` (viewBox 640 × 640), 24 × 24 dp.

`ic_eye_slash.xml` has the path of `svgs/solid/eye-slash.svg` of Font Awesome 7 (viewBox 576 × 512), whose slash goes
past the viewBox (from y = -24.9 to about 537). The web shows it, the CSS of Font Awesome lets it overflow; a vector
drawable cuts it at its viewport, so both round ends of the slash are flat.

Fix: the paths of `svgs-full/solid/` (viewBox 640 × 640) for both icons, `viewportWidth/Height` 640, 24 × 24 dp.

## Should fix

### ~~`GB-3` The bought items have no end and are read whole for every page~~

**Fixed:** the eye shows the latest bought item of each name that is not to buy (a window in SQL); the list has one row for each name, not one for each time it was bought.

With `checked=true`, `GroceryService.items` reads and sorts all items of the list ever bought and cuts one page out
in memory; the web (`fetchAll`) and Android (`all()`) ask for every page. A list with 5000 bought items costs 50
requests that read 5000 rows each, again on every change event of every client with the eye open; the list shows
"Milk" once for each time it was bought.

Fix: a decision for `GROCERY-14`: e.g. only the latest bought item of each name (the window of the suggestions), or
the ones of the last weeks; the page in SQL.

### ~~`GB-4` Forgetting a suggestion sends no change event~~

**Fixed:** forgetting sends the change event of the list.

`GroceryService.forget` deletes bought items, which the eye shows, without `changes.changed(listId)`. User A has the
eye open on a shared list, user B forgets "Milk": A keeps seeing the bought Milk, and a change of it gives 404.

Fix: send the change event when something was forgotten; a test with the other change events.

### ~~`GB-5` Web: a reload after the page is left throws~~

**Fixed:** a request that answers after the page is gone reloads nothing.

With the eye on, a user taps Milk and leaves the page: `onDestroy` sends the check, and its answer reloads the items
with `takeUntilDestroyed` of a destroyed component (NG0911, to the error handler). The error path of `send` had the
same problem before; the reload after a check makes it happen on success too.

Fix: no reload once the component is destroyed.

### ~~`GB-6` A check races another device and a reload~~

**Fixed:** a check answered 404 (the item is bought or gone) is done, no error; the web sends no check for an item a reload shows bought; on Android the load after the eye waits for the check.

Web: an item that another device checks during its undo window comes back bought with the next change event; the
check then gets 404 and shows an error, and a second tap no longer undoes. Android: the eye toggled in the undo window
sends the check and loads the items at the same time, so the item can show as to buy again until the next change
event.

Fix: a check of an item already bought is done, not an error; on Android the load waits for the check.

### ~~`GB-7` Android: the red line is wrong on a name of two lines~~

**Fixed:** one red line through each line of the text, from its layout, also right to left.

`redStrikeThrough` draws one line at half the height of the whole text: for a name that wraps (a long name, a large
font) it runs between the lines, as long as the widest line.

Fix: one line for each line of the text, from its `TextLayoutResult` (also right to left).

### ~~`GB-8` Android: the Rename dialog checks while typing and has no Done~~

**Fixed:** the error shows once the field was left or Done was pressed; Done saves; the field capitalizes sentences.

`RenameItem` shows the error of the name while typing (`UX-6`: when the field is left), has no IME action Done that
saves, and no `KeyboardCapitalization.Sentences`, which every other name field has.

Fix: the error once the field was left or Save was tried; `imeAction = Done` that saves a valid name; the
capitalization.

## Small items

- ~~`GB-9` The count of the items to buy (`ITEMS_TO_BUY`, `GET /lists`) and the names to buy of the suggestions read
  every row of the list, its history too. A partial index for sqlite on `grocery_items (list_id) WHERE deleted_at IS
  NULL AND checked_at IS NULL`.~~ **Fixed:** a partial index for sqlite, `grocery_items_to_buy_idx`.
- ~~`GB-10` The suggestion query numbers the whole history before it filters by the text and leaves out the names to
  buy; both filter on the name, so they can go into the inner query, which runs on every key typed.~~ **Fixed:** the text and the names to buy filter the inner query.
- ~~`GB-11` Comments that still speak of products and what a list learned: `GroceryService.kt` (`addItem`),
  `GroceryNewItemRequest.kt`, `GrocerySuggestionResponse.kt`, `GroceryNameKey.kt`, the suggestion route ("bought
  most"; the order is how many live items of the name), the variable `products` in `GroceryRepositoryTest`.~~ **Fixed:** the comments and the descriptions speak of the latest item of a name.
- ~~`GB-12` FDR-004 marks as unused the files "no live note refers to"; done that way, it deletes every photo of a
  grocery item. The rule needs "and no live grocery item".~~ **Fixed:** FDR-004 says "no live note and no live grocery item".
- ~~`GB-13` `DELETE /lists/{id}/suggestions/{id}` takes the id of any live item of the list, also one to buy; harmless
  (only the bought items of the name go), the description says "the latest item of its name".~~ **Fixed:** the description says that any live item of the list with the name does.
- ~~`GB-14` Web: Rename of an item in its undo window stays open after the check; the reload brings the item back
  bought with the field open and without focus. Close the Rename when its item leaves the list.~~ **Fixed:** the Rename field closes when its item is checked, deleted or no longer in the list.
- ~~`GB-15` Web: after Enter or Escape in the Rename field the focus goes to the body; put it on the ⋮ of the row.~~ **Fixed:** after Enter or Escape the focus goes back to the row.
- ~~`GB-16` The eye: on the web its label changes (Show/Hide) and it has `aria-pressed`, a screen reader reads both; on
  Android it is an action without a state. One of the two on the web; `IconToggleButton` on Android.~~ **Fixed:** the web keeps one label with `aria-pressed`; Android has an `IconToggleButton`.
- ~~`GB-17` Web: Rename is disabled while its item is renamed, without a tooltip that says why (`UX-12`).~~ **Fixed:** the disabled Rename has the tooltip "The name is changed in its row."
- ~~`GB-18` Web: a bought item set to another category shows before the items to buy of that category until the next
  reload; sort the items to buy before the bought ones in each group, as the backend does.~~ **Fixed:** in each group the items to buy come first, then the bought ones, the last bought first.
- ~~`GB-19` Android: the Rename and the Delete dialog close without a word when their row goes, e.g. after a change
  event; the typed name is lost. Keep them in the model, as the category screen.~~ **Fixed:** the dialogs are state of the model; they stay when their row goes, back closes them.
- ~~`GB-20` German says "verbergen" on the web and "ausblenden" on Android for hiding the bought items; on Android
  "Nothing to buy" does not show while only bought items show; in `GroceryApi.kt` the comment of `deleteItem` is
  above `updateItem`.~~ **Fixed:** "ausblenden" on both; "Nothing to buy" shows whenever nothing is to buy; the comment is above `deleteItem`.
- ~~`GB-21` Tests that are missing: a Delete and a Rename in the undo window (`GB-1`); the eye toggled in the undo
  window; a check with the eye on reloads with `checked=true`; a change event keeps it; the eye reset when another
  list opens (Android); a failed load after the toggle; forgetting sends a change event (`GB-4`); one PUT for Enter
  and a blur.~~ **Fixed:** the tests are added on the web, on Android and in the backend.
