import { Route } from '@angular/router';
import { authGuard, pageTitle } from '@sak/core';

export const settingsRoute: Route = {
  path: 'settings',
  loadComponent: () => import('./settings-page').then((m) => m.SettingsPage),
  canActivate: [authGuard],
  title: pageTitle('shared.settings'),
  // a tab for every scope, the first is opened without one
  children: [
    {
      path: ':scope',
      loadComponent: () => import('./settings-scope-tab').then((m) => m.SettingsScopeTab),
    },
  ],
};

// the settings of the user signed in, every role has them
export const userSettingsRoute: Route = {
  path: 'user/settings',
  loadComponent: () => import('./user-settings-page').then((m) => m.UserSettingsPage),
  canActivate: [authGuard],
  title: pageTitle('shared.my_settings'),
};
