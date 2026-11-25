import { Route } from '@angular/router';
import { authGuard, pageTitle } from '@sak/core';

export const statusRoute: Route = {
  path: 'status',
  loadComponent: () => import('./status-page').then((m) => m.StatusPage),
  canActivate: [authGuard],
  title: pageTitle('status.title'),
};

// where the health was before it was part of the status
export const healthRedirect: Route = { path: 'health', redirectTo: 'status' };
