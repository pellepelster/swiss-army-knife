import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';
import { GroceryListForm } from './grocery-list-form';
import { GroceryListsPage } from './grocery-lists-page';
import { GroceryPage } from './grocery-page';

// the grocery app below /grocery, behind the login: all lists first (LIST-3), then one list, a page
// to create a list and one to edit it. It installs as an app of its own and has its own favicon
export const groceryRoute: Route = {
  path: 'grocery',
  canActivate: [authGuard],
  data: {
    favicon: { svg: 'icons/grocery.svg', png: 'icons/grocery-192.png' },
    app: { manifest: 'grocery.webmanifest', appleTouchIcon: 'icons/grocery-apple-touch-180.png' },
  },
  children: [
    { path: '', component: GroceryListsPage, title: pageTitle('grocery.title') },
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
