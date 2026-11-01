import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { UserCreate } from './user-create';
import { UserList } from './user-list';

// who may sign in, below /users behind the login, for an admin; a maintenance page, no app
export const usersRoute: Route = {
  path: 'users',
  canActivate: [authGuard],
  children: [
    { path: '', component: UserList, title: 'Users' },
    { path: 'create', component: UserCreate, title: 'Add user · Users' },
  ],
};
