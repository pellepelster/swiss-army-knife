import { Routes } from '@angular/router';
import { pageTitle } from '@sak/core';
import { RepositoryList } from './repository-list';

// loaded with the app (backups.routes.ts)
export const BACKUPS_PAGES: Routes = [
  { path: '', component: RepositoryList, title: pageTitle('app.backups') },
];
