import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { pageTitle } from './i18n/i18n';
import { HealthPage } from './health/health-page';
import { Home } from './home/home';
import { hostingRoute } from './hosting/hosting.routes';
import { redirectsRoute } from './redirects/redirects.routes';
import { groceryDownloadRoute, groceryRoute } from './grocery/grocery.routes';
import { Login } from './login/login';
import { NotesPage } from './notes/notes-page';
import { SettingsPage } from './settings/settings-page';
import { SettingsScopeTab } from './settings/settings-scope-tab';
import { Signin } from './signin/signin';
import { usersRoute } from './users/users.routes';

// every app needs a session, without one the login is shown and leads back afterwards; an app
// names its favicon in its data, the others show the swiss army knife (FaviconService)
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: pageTitle('home.title') },
  {
    path: 'notes',
    component: NotesPage,
    canActivate: [authGuard],
    title: pageTitle('app.notes'),
    data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  hostingRoute,
  redirectsRoute,
  groceryDownloadRoute,
  groceryRoute,
  {
    path: 'health',
    component: HealthPage,
    canActivate: [authGuard],
    title: pageTitle('health.title'),
  },
  usersRoute,
  {
    path: 'settings',
    component: SettingsPage,
    canActivate: [authGuard],
    title: pageTitle('shared.settings'),
    // a tab for every scope, the first is opened without one
    children: [{ path: ':scope', component: SettingsScopeTab }],
  },
  { path: 'login', component: Login, title: pageTitle('login.sign_in') },
  { path: 'signin', component: Signin, title: pageTitle('signin.page_title') },
  { path: '**', redirectTo: '' },
];
