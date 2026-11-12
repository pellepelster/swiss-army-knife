import { Routes } from '@angular/router';
import { pageTitle } from '../i18n/i18n';
import { UserCreate } from './user-create';
import { UserList } from './user-list';

// who may sign in, loaded with the first of its pages (users.routes.ts)
export const USERS_PAGES: Routes = [
  { path: '', component: UserList, title: pageTitle('users.title') },
  { path: 'create', component: UserCreate, title: pageTitle('users.create_title') },
];
