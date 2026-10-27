import { Routes } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { Favicon } from '../favicon.service';
import { BucketConfiguration } from './bucket-configuration';
import { BucketCreate } from './bucket-create';
import { BucketCredentials } from './bucket-credentials';
import { BucketEdit } from './bucket-edit';
import { BucketList } from './bucket-list';
import { BucketView } from './bucket-view';

export const HOSTING_FAVICON: Favicon = { svg: 'icons/hosting.svg', png: 'icons/hosting-192.png' };

// the buckets of the s3 api: the list, a page to create one, a bucket's page with its tabs and a
// page to edit it; create and edit come first, the bucket's page would take them as its name
export const hostingRoutes: Routes = [
  { path: '', component: BucketList, title: 'Hosting' },
  { path: 'create', component: BucketCreate, title: 'Create bucket · Hosting' },
  { path: ':name/edit', component: BucketEdit, title: 'Edit bucket · Hosting' },
  {
    path: ':name',
    component: BucketView,
    title: 'Bucket · Hosting',
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'configuration' },
      { path: 'configuration', component: BucketConfiguration },
      { path: 'credentials', component: BucketCredentials },
    ],
  },
];

// the hosting app below /hosting, behind the login
export const hostingRoute = {
  path: 'hosting',
  canActivate: [authGuard],
  data: { favicon: HOSTING_FAVICON },
  children: hostingRoutes,
};
