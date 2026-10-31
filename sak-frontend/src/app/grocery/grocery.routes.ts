import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { GroceryListForm } from './grocery-list-form';
import { GroceryPage } from './grocery-page';

// the grocery app below /grocery, behind the login: the list shown (its id in the query parameter
// list), a page to create a list and one to edit it
export const groceryRoute: Route = {
  path: 'grocery',
  canActivate: [authGuard],
  children: [
    { path: '', component: GroceryPage, title: 'Grocery' },
    { path: 'lists/create', component: GroceryListForm, title: 'Create list · Grocery' },
    { path: 'lists/:id/edit', component: GroceryListForm, title: 'Edit list · Grocery' },
  ],
};
