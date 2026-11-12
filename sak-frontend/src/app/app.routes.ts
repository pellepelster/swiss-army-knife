import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { pageTitle } from './i18n/i18n';
import { HealthPage } from './health/health-page';
import { Home } from './home/home';
import { hostingRoute } from './hosting/hosting.routes';
import { redirectsRoute } from './redirects/redirects.routes';
import { groceryDownloadRoute, groceryRoute } from './grocery/grocery.routes';
import { todoDownloadRoute, todoRoute } from './todo/todo.routes';
import { AppDownload } from './shared/app-download';
import { DownloadsPage } from './downloads/downloads-page';
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
  todoDownloadRoute,
  // the android app of the notes to download, without a session; before the notes, whose login
  // guard would take it otherwise
  {
    path: 'notes/download/app',
    component: AppDownload,
    title: pageTitle('notes.download.page_title'),
    data: { app: 'notes', favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  groceryRoute,
  todoRoute,
  // the download links of the android apps, without a session so the link opens on a phone
  { path: 'downloads', component: DownloadsPage, title: pageTitle('downloads.page_title') },
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
