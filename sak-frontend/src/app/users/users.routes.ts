import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';
import { UserCreate } from './user-create';
import { UserList } from './user-list';

// who may sign in, below /users behind the login, for an admin; a maintenance page, no app
export const usersRoute: Route = {
  path: 'users',
  canActivate: [authGuard],
  children: [
    { path: '', component: UserList, title: pageTitle('users.title') },
    { path: 'create', component: UserCreate, title: pageTitle('users.create_title') },
  ],
};
