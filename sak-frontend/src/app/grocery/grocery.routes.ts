import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';
import { AppDownload } from '../shared/app-download';
import { LIST_APP, ListApp } from '../shared/list-app';
import { ListsPage } from '../shared/lists-page';
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
    empty: 'grocery.lists.empty',
    countNone: 'grocery.lists.to_buy_none',
    countOne: 'grocery.lists.to_buy_one',
    countOther: 'grocery.lists.to_buy_other',
    sharedBadge: 'grocery.shared_badge',
    settingsOf: 'grocery.settings_of',
    listsNotLoaded: 'grocery.lists_not_loaded',
    listsForbidden: 'grocery.lists_forbidden',
    onlyOwnerShares: 'grocery.only_owner_shares',
    onlyOwnerDeletes: 'grocery.only_owner_deletes',
    listGone: 'grocery.list_gone',
    back: 'grocery.back',
    create: 'grocery.form.create',
    icon: 'grocery.form.icon',
    chooseIcon: 'grocery.form.choose_icon',
    noIcon: 'grocery.form.no_icon',
    shared: 'grocery.form.shared',
    sharedHint: 'grocery.form.shared_hint',
    deleteList: 'grocery.form.delete_list',
    noSuchList: 'grocery.form.no_such_list',
    notLoaded: 'grocery.form.not_loaded',
    notSaved: 'grocery.form.not_saved',
    notDeleted: 'grocery.form.not_deleted',
  },
};

// the android app of the grocery lists to download, without a session; before groceryRoute, whose
// login guard would take it otherwise
export const groceryDownloadRoute: Route = {
  path: 'grocery/download/app',
  component: AppDownload,
  title: pageTitle('grocery.download.page_title'),
  data: { app: 'grocery', favicon: { svg: 'icons/grocery.svg', png: 'icons/grocery-192.png' } },
};

// the grocery app below /grocery, behind the login: all lists first (LIST-3), then one list, a page
// to create a list and one to edit it. It installs as an app of its own and has its own favicon
export const groceryRoute: Route = {
  path: 'grocery',
  canActivate: [authGuard],
  providers: [{ provide: LIST_APP, useValue: GROCERY_LIST_APP }],
  data: {
    favicon: { svg: 'icons/grocery.svg', png: 'icons/grocery-192.png' },
    app: { manifest: 'grocery.webmanifest', appleTouchIcon: 'icons/grocery-apple-touch-180.png' },
  },
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
};
