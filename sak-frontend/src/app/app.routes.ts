import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { pageTitle } from './i18n/i18n';
import { Home } from './home/home';
import { hostingRoute } from './hosting/hosting.routes';
import { redirectsRoute } from './redirects/redirects.routes';
import { groceryDownloadRoute, groceryRoute } from './grocery/grocery.routes';
import { todoDownloadRoute, todoRoute } from './todo/todo.routes';
import { Login } from './login/login';
import { Signin } from './signin/signin';
import { usersRoute } from './users/users.routes';

// every app needs a session, without one the login is shown and leads back afterwards; an app
// names its favicon in its data, the others show the swiss army knife (FaviconService). Only the
// home, the login and the sign-in are loaded at the start, every other page comes with its first
// visit (TD-5)
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: pageTitle('home.title') },
  {
    path: 'notes',
    loadComponent: () => import('./notes/notes-page').then((m) => m.NotesPage),
    canActivate: [authGuard],
    title: pageTitle('app.notes'),
    data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  hostingRoute,
  redirectsRoute,
  groceryDownloadRoute,
  todoDownloadRoute,
  // the android app of the notes to download, without a session; before the notes, whose login
  // guard would take it otherwise
  {
    path: 'notes/download/app',
    loadComponent: () => import('./shared/app-download').then((m) => m.AppDownload),
    title: pageTitle('notes.download.page_title'),
    data: { app: 'notes', favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  groceryRoute,
  todoRoute,
  // the download links of the android apps, without a session so the link opens on a phone
  {
    path: 'downloads',
    loadComponent: () => import('./downloads/downloads-page').then((m) => m.DownloadsPage),
    title: pageTitle('downloads.page_title'),
  },
  {
    path: 'health',
    loadComponent: () => import('./health/health-page').then((m) => m.HealthPage),
    canActivate: [authGuard],
    title: pageTitle('health.title'),
  },
  usersRoute,
  {
    path: 'settings',
    loadComponent: () => import('./settings/settings-page').then((m) => m.SettingsPage),
    canActivate: [authGuard],
    title: pageTitle('shared.settings'),
    // a tab for every scope, the first is opened without one
    children: [
      {
        path: ':scope',
        loadComponent: () =>
          import('./settings/settings-scope-tab').then((m) => m.SettingsScopeTab),
      },
    ],
  },
  // the QR code that connects an app, with the qr generator, which only this page needs
  {
    path: 'connect-app',
    loadComponent: () => import('./connect/connect-page').then((m) => m.ConnectPage),
    canActivate: [authGuard],
    title: pageTitle('connect.title'),
  },
  { path: 'login', component: Login, title: pageTitle('login.sign_in') },
  { path: 'signin', component: Signin, title: pageTitle('signin.page_title') },
  { path: '**', redirectTo: '' },
];
