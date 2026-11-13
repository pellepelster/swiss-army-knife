import { Routes } from '@angular/router';
import { pageTitle } from '../i18n/i18n';
import { RedirectConfiguration } from './redirect-configuration';
import { RedirectCreate } from './redirect-create';
import { RedirectEdit } from './redirect-edit';
import { RedirectList } from './redirect-list';
import { RedirectView } from './redirect-view';

// loaded with the first of these pages (redirects.routes.ts); create and edit come first, the
// redirect's page would take them as its domain
export const REDIRECTS_PAGES: Routes = [
  { path: '', component: RedirectList, title: pageTitle('app.redirects') },
  { path: 'create', component: RedirectCreate, title: pageTitle('redirects.route.create') },
  { path: ':source/edit', component: RedirectEdit, title: pageTitle('redirects.route.edit') },
  {
    path: ':source',
    component: RedirectView,
    title: pageTitle('redirects.route.redirect'),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'configuration' },
      { path: 'configuration', component: RedirectConfiguration },
    ],
  },
];
