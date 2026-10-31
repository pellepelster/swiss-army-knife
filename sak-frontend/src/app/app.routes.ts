import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { HealthPage } from './health/health-page';
import { Home } from './home/home';
import { hostingRoute } from './hosting/hosting.routes';
import { redirectsRoute } from './redirects/redirects.routes';
import { Login } from './login/login';
import { NotesPage } from './notes/notes-page';
import { SettingsPage } from './settings/settings-page';
import { SettingsScopeTab } from './settings/settings-scope-tab';
import { Signin } from './signin/signin';

// every app needs a session, without one the login is shown and leads back afterwards; an app
// names its favicon in its data, the others show the swiss army knife (FaviconService)
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: 'Apps' },
  {
    path: 'notes',
    component: NotesPage,
    canActivate: [authGuard],
    title: 'Notes',
    data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  hostingRoute,
  redirectsRoute,
  { path: 'health', component: HealthPage, canActivate: [authGuard], title: 'Health' },
  {
    path: 'settings',
    component: SettingsPage,
    canActivate: [authGuard],
    title: 'Settings',
    // a tab for every scope, the first is opened without one
    children: [{ path: ':scope', component: SettingsScopeTab }],
  },
  { path: 'login', component: Login, title: 'Sign in' },
  { path: 'signin', component: Signin, title: 'Signing in' },
  { path: '**', redirectTo: '' },
];
