import { Routes } from '@angular/router';
import { backupsRoute } from '@sak/backups';
import { authGuard, pageTitle } from '@sak/core';
import { groceryDownloadRoute, groceryRoute } from '@sak/grocery';
import { healthRoute } from '@sak/health';
import { hostingRoute } from '@sak/hosting';
import { notesDownloadRoute, notesRoute } from '@sak/notes';
import { redirectsRoute } from '@sak/redirects';
import { settingsRoute } from '@sak/settings';
import { todoDownloadRoute, todoRoute } from '@sak/todo';
import { usersRoute } from '@sak/users';
import { Home } from './home/home';
import { Login } from './login/login';
import { Signin } from './signin/signin';

// the routes of the apps are theirs (projects/<app>), here they are put in their order. Every app
// needs a session, without one the login is shown and leads back afterwards; an app names its
// favicon in its data, the others show the swiss army knife (FaviconService). Only the home, the
// login and the sign-in are loaded at the start, every other page on its first visit, so the start
// bundle stays small
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: pageTitle('home.title') },
  notesRoute,
  hostingRoute,
  redirectsRoute,
  backupsRoute,
  // the downloads of the android apps before the apps, whose login guard would take them otherwise
  groceryDownloadRoute,
  todoDownloadRoute,
  notesDownloadRoute,
  groceryRoute,
  todoRoute,
  // the download links of the android apps, without a session so the link opens on a phone
  {
    path: 'downloads',
    loadComponent: () => import('./downloads/downloads-page').then((m) => m.DownloadsPage),
    title: pageTitle('downloads.page_title'),
  },
  healthRoute,
  usersRoute,
  settingsRoute,
  // the QR code that connects an app, with the qr generator, which only this page needs
  {
    path: 'connect-app',
    loadComponent: () => import('./connect/connect-page').then((m) => m.ConnectPage),
    canActivate: [authGuard],
    title: pageTitle('connect.title'),
  },
  // a desktop app on this computer asks for a session (SECURITY-3)
  {
    path: 'connect-desktop',
    loadComponent: () => import('./connect/connect-desktop-page').then((m) => m.ConnectDesktopPage),
    canActivate: [authGuard],
    title: pageTitle('connect_desktop.title'),
  },
  { path: 'login', component: Login, title: pageTitle('login.sign_in') },
  { path: 'signin', component: Signin, title: pageTitle('signin.page_title') },
  { path: '**', redirectTo: '' },
];
