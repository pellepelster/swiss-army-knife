import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';

// the android app of the grocery lists to download, without a session; before groceryRoute, whose
// login guard would take it otherwise
export const groceryDownloadRoute: Route = {
  path: 'grocery/download/app',
  loadComponent: () => import('../shared/app-download').then((m) => m.AppDownload),
  title: pageTitle('grocery.download.page_title'),
  data: { app: 'grocery', favicon: { svg: 'icons/grocery.svg', png: 'icons/grocery-192.png' } },
};

// the grocery app below /grocery, behind the login: all lists first (LIST-3), then one list, a page
// to create a list and one to edit it. It installs as an app of its own and has its own favicon
export const groceryRoute: Route = {
  path: 'grocery',
  canActivate: [authGuard],
  data: {
    favicon: { svg: 'icons/grocery.svg', png: 'icons/grocery-192.png' },
    app: { manifest: 'grocery.webmanifest', appleTouchIcon: 'icons/grocery-apple-touch-180.png' },
  },
  // the pages and what they need come with the first of them
  loadChildren: () => import('./grocery.pages').then((m) => m.GROCERY_PAGES),
};
