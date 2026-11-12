import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';

const HOSTING_FAVICON: Favicon = { svg: 'icons/hosting.svg', png: 'icons/hosting-192.png' };

// the static hosting app below /static-hosting, behind the login; its pages come with the first of
// them (hosting.pages.ts)
export const hostingRoute: Route = {
  path: 'static-hosting',
  canActivate: [authGuard],
  data: { favicon: HOSTING_FAVICON },
  loadChildren: () => import('./hosting.pages').then((m) => m.HOSTING_PAGES),
};
