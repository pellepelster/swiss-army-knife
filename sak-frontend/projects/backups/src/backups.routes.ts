import { Route } from '@angular/router';
import { authGuard, Favicon } from '@sak/core';

const BACKUPS_FAVICON: Favicon = {
  svg: 'icons/backups.svg',
  png: 'icons/backups-192.png',
};

// the backups app below /backups, behind the login; its page comes with it (backups.pages.ts)
export const backupsRoute: Route = {
  path: 'backups',
  canActivate: [authGuard],
  data: { favicon: BACKUPS_FAVICON },
  loadChildren: () => import('./backups.pages').then((m) => m.BACKUPS_PAGES),
};
