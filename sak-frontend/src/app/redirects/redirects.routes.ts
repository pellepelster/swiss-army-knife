import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';
import { RedirectCreate } from './redirect-create';
import { RedirectList } from './redirect-list';

export const REDIRECTS_FAVICON: Favicon = {
  svg: 'icons/redirects.svg',
  png: 'icons/redirects-192.png',
};

// the redirects app below /redirects, behind the login: the list and a page to create one
export const redirectsRoute = {
  path: 'redirects',
  canActivate: [authGuard],
  data: { favicon: REDIRECTS_FAVICON },
  children: [
    { path: '', component: RedirectList, title: 'Redirects' },
    { path: 'create', component: RedirectCreate, title: 'Create redirect · Redirects' },
  ],
};
