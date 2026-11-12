import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';

// the android app of the todo lists to download, without a session; before todoRoute, whose login
// guard would take it otherwise
export const todoDownloadRoute: Route = {
  path: 'todo/download/app',
  loadComponent: () => import('../shared/app-download').then((m) => m.AppDownload),
  title: pageTitle('todo.download.page_title'),
  data: { app: 'todo', favicon: { svg: 'icons/todo.svg', png: 'icons/todo-192.png' } },
};

// the todo app below /todo, behind the login: all lists first (LIST-3), then one list, a page to
// create a list and one to edit it. It installs as an app of its own and has its own favicon
export const todoRoute: Route = {
  path: 'todo',
  canActivate: [authGuard],
  data: {
    favicon: { svg: 'icons/todo.svg', png: 'icons/todo-192.png' },
    app: { manifest: 'todo.webmanifest', appleTouchIcon: 'icons/todo-apple-touch-180.png' },
  },
  // the pages and what they need come with the first of them
  loadChildren: () => import('./todo.pages').then((m) => m.TODO_PAGES),
};
