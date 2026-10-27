import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';
import { RedirectConfiguration } from './redirect-configuration';
import { RedirectCreate } from './redirect-create';
import { RedirectEdit } from './redirect-edit';
import { RedirectList } from './redirect-list';
import { RedirectView } from './redirect-view';

export const REDIRECTS_FAVICON: Favicon = {
  svg: 'icons/redirects.svg',
  png: 'icons/redirects-192.png',
};

// the redirects app below /redirects, behind the login: the list, a page to create one, a
// redirect's page with its tabs and a page to edit it; create and edit come first, the
// redirect's page would take them as its domain
export const redirectsRoute: Route = {
  path: 'redirects',
  canActivate: [authGuard],
  data: { favicon: REDIRECTS_FAVICON },
  children: [
    { path: '', component: RedirectList, title: 'Redirects' },
    { path: 'create', component: RedirectCreate, title: 'Create redirect · Redirects' },
    { path: ':source/edit', component: RedirectEdit, title: 'Edit redirect · Redirects' },
    {
      path: ':source',
      component: RedirectView,
      title: 'Redirect · Redirects',
      children: [
        { path: '', pathMatch: 'full', redirectTo: 'configuration' },
        { path: 'configuration', component: RedirectConfiguration },
      ],
    },
  ],
};
