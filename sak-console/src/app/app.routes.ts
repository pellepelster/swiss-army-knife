import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { Home } from './home/home';
import { Redirects } from './ingress/redirects';
import { LiveLogs } from './logs/live-logs';
import { Login } from './login/login';
import { Signin } from './signin/signin';
import { BucketDetail } from './s3/bucket';
import { BucketConfiguration } from './s3/bucket-configuration';
import { BucketCredentials } from './s3/bucket-credentials';
import { BucketEdit } from './s3/bucket-edit';
import { Buckets } from './s3/buckets';
import { SiteMetrics } from './sites/site-metrics';

export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: 'sak console' },
  { path: 'logs', pathMatch: 'full', redirectTo: 'logs/statistics' },
  // without a site the first one is shown
  {
    path: 'logs/statistics',
    component: SiteMetrics,
    canActivate: [authGuard],
    title: 'Log statistics · sak console',
  },
  {
    path: 'logs/statistics/:site',
    component: SiteMetrics,
    canActivate: [authGuard],
    title: 'Log statistics · sak console',
  },
  { path: 'logs/live', pathMatch: 'full', redirectTo: 'logs/live/all' },
  {
    path: 'logs/live/:site',
    component: LiveLogs,
    canActivate: [authGuard],
    title: 'Live log · sak console',
  },
  { path: 's3', pathMatch: 'full', redirectTo: 's3/buckets' },
  {
    path: 's3/buckets',
    component: Buckets,
    canActivate: [authGuard],
    title: 'Buckets · sak console',
  },
  // before the bucket, whose tabs would take the path
  {
    path: 's3/buckets/:name/edit',
    component: BucketEdit,
    canActivate: [authGuard],
    title: 'Edit bucket · sak console',
  },
  {
    path: 's3/buckets/:name',
    component: BucketDetail,
    canActivate: [authGuard],
    title: 'Bucket · sak console',
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'configuration' },
      { path: 'configuration', component: BucketConfiguration },
      { path: 'credentials', component: BucketCredentials },
    ],
  },
  {
    path: 'ingress/redirects',
    component: Redirects,
    canActivate: [authGuard],
    title: 'Redirects · sak console',
  },
  { path: 'login', component: Login, title: 'Sign in · sak console' },
  { path: 'signin', component: Signin, title: 'Signing in · sak console' },
  { path: '**', redirectTo: '' },
];
