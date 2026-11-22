import { Route } from '@angular/router';
import { authGuard, pageTitle } from '@sak/core';

export const healthRoute: Route = {
  path: 'health',
  loadComponent: () => import('./health-page').then((m) => m.HealthPage),
  canActivate: [authGuard],
  title: pageTitle('health.title'),
};
