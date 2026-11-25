import { Routes } from '@angular/router';
import { LIST_APP, ListApp, ListsPage, pageTitle } from '@sak/core';
import { GroceryChanges } from './grocery-changes';
import { GroceryListForm } from './grocery-list-form';
import { GroceryPage } from './grocery-page';
import { GroceryService } from './grocery.service';

// the grocery app for the pages all list-based apps share
export const GROCERY_LIST_APP: ListApp = {
  route: '/grocery',
  service: GroceryService,
  changes: GroceryChanges,
  texts: {
    title: 'app.grocery',
    lists: 'grocery.lists.label',
    empty: 'lists.empty',
    countNone: 'grocery.lists.to_buy_none',
    countOne: 'grocery.lists.to_buy_one',
    countOther: 'grocery.lists.to_buy_other',
    sharedBadge: 'lists.shared_badge',
    settingsOf: 'lists.settings_of',
    listsNotLoaded: 'lists.not_loaded',
    listsForbidden: 'grocery.lists_forbidden',
    onlyOwnerShares: 'grocery.only_owner_shares',
    onlyOwnerDeletes: 'lists.only_owner_deletes',
    listGone: 'lists.list_gone',
    back: 'lists.back',
    create: 'lists.form.create',
    icon: 'lists.form.icon',
    chooseIcon: 'lists.form.choose_icon',
    noIcon: 'lists.form.no_icon',
    shared: 'lists.form.shared',
    sharedHint: 'grocery.form.shared_hint',
    deleteList: 'lists.form.delete_list',
    noSuchList: 'lists.form.no_such_list',
    notLoaded: 'lists.form.not_loaded',
    notSaved: 'lists.form.not_saved',
    notDeleted: 'lists.form.not_deleted',
  },
};

// all lists first (LIST-3)
export const GROCERY_PAGES: Routes = [
  {
    path: '',
    providers: [{ provide: LIST_APP, useValue: GROCERY_LIST_APP }],
    children: [
      { path: '', component: ListsPage, title: pageTitle('app.grocery') },
      {
        path: 'lists/create',
        component: GroceryListForm,
        title: pageTitle('grocery.page_title.create'),
      },
      { path: 'lists/:id', component: GroceryPage, title: pageTitle('grocery.page_title.list') },
      {
        path: 'lists/:id/edit',
        component: GroceryListForm,
        title: pageTitle('grocery.page_title.settings'),
      },
    ],
  },
];
