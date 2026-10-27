import { Routes } from '@angular/router';
import { ConsoleShell } from './console-shell';
import { LiveLogs } from './logs/live-logs';
import { ConsoleOverview } from './overview';
import { SiteMetrics } from './sites/site-metrics';

// the pages of the console below its frame: the overview of the requests and the buckets'
// data, the statistics of the sites and their live log; loaded with the first page of /console,
// so the other apps do not carry the charts
export const consoleRoutes: Routes = [
  {
    path: '',
    component: ConsoleShell,
    children: [
      { path: '', component: ConsoleOverview, title: 'Console' },
      { path: 'logs', pathMatch: 'full', redirectTo: 'logs/statistics' },
      // without a site the first one is shown
      { path: 'logs/statistics', component: SiteMetrics, title: 'Log statistics · Console' },
      { path: 'logs/statistics/:site', component: SiteMetrics, title: 'Log statistics · Console' },
      { path: 'logs/live', pathMatch: 'full', redirectTo: 'logs/live/all' },
      { path: 'logs/live/:site', component: LiveLogs, title: 'Live log · Console' },
    ],
  },
];
