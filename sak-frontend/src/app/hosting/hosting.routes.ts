import { Routes } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';
import { BucketConfiguration } from './bucket-configuration';
import { BucketCreate } from './bucket-create';
import { BucketCredentials } from './bucket-credentials';
import { BucketEdit } from './bucket-edit';
import { BucketList } from './bucket-list';
import { BucketPublishing } from './bucket-publishing';
import { BucketView } from './bucket-view';

const HOSTING_FAVICON: Favicon = { svg: 'icons/hosting.svg', png: 'icons/hosting-192.png' };

// the buckets of the s3 api: the list, a page to create one, a bucket's page with its tabs and a
// page to edit it; create and edit come first, the bucket's page would take them as its name
export const hostingRoutes: Routes = [
  { path: '', component: BucketList, title: 'Static hosting' },
  { path: 'create', component: BucketCreate, title: 'Create bucket · Static hosting' },
  { path: ':name/edit', component: BucketEdit, title: 'Edit bucket · Static hosting' },
  {
    path: ':name',
    component: BucketView,
    title: 'Bucket · Static hosting',
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

// the static hosting app below /static-hosting, behind the login
export const hostingRoute = {
  path: 'static-hosting',
  canActivate: [authGuard],
  data: { favicon: HOSTING_FAVICON },
  children: hostingRoutes,
};
