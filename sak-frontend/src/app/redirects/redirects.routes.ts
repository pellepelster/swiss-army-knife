import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';

const REDIRECTS_FAVICON: Favicon = {
  svg: 'icons/redirects.svg',
  png: 'icons/redirects-192.png',
};

// the redirects app below /redirects, behind the login; its pages come with the first of them
// (redirects.pages.ts)
export const redirectsRoute: Route = {
  path: 'redirects',
  canActivate: [authGuard],
  data: { favicon: REDIRECTS_FAVICON },
  loadChildren: () => import('./redirects.pages').then((m) => m.REDIRECTS_PAGES),
};
