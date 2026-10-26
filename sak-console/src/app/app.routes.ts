import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { Home } from './home/home';
import { LiveLogs } from './logs/live-logs';
import { Login } from './login/login';
import { Signin } from './signin/signin';
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
  { path: 'login', component: Login, title: 'Sign in · sak console' },
  { path: 'signin', component: Signin, title: 'Signing in · sak console' },
  { path: '**', redirectTo: '' },
];
