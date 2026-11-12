import { Routes } from '@angular/router';
import { pageTitle } from '../i18n/i18n';
import { BucketConfiguration } from './bucket-configuration';
import { BucketCreate } from './bucket-create';
import { BucketCredentials } from './bucket-credentials';
import { BucketEdit } from './bucket-edit';
import { BucketList } from './bucket-list';
import { BucketPublishing } from './bucket-publishing';
import { BucketView } from './bucket-view';

// the buckets of the s3 api, loaded with the first of their pages (hosting.routes.ts): the list, a
// page to create one, a bucket's page with its tabs and a page to edit it; create and edit come
// first, the bucket's page would take them as its name
export const HOSTING_PAGES: Routes = [
  { path: '', component: BucketList, title: pageTitle('app.static_hosting') },
  { path: 'create', component: BucketCreate, title: pageTitle('hosting.route.create') },
  { path: ':name/edit', component: BucketEdit, title: pageTitle('hosting.route.edit') },
  {
    path: ':name',
    component: BucketView,
    title: pageTitle('hosting.route.bucket'),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'configuration' },
      { path: 'configuration', component: BucketConfiguration },
      { path: 'credentials', component: BucketCredentials },
      { path: 'publishing', component: BucketPublishing },
      // with the charts, which only this tab needs
      { path: 'logs', loadComponent: () => import('./bucket-logs').then((m) => m.BucketLogs) },
    ],
  },
];
