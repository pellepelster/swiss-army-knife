import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';

// who may sign in, below /users behind the login, for an admin; a maintenance page, no app. Its
// pages come with the first of them (users.pages.ts)
export const usersRoute: Route = {
  path: 'users',
  canActivate: [authGuard],
  loadChildren: () => import('./users.pages').then((m) => m.USERS_PAGES),
};
