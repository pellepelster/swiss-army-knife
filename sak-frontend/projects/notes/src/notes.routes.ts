import { Route } from '@angular/router';
import { authGuard, pageTitle } from '@sak/core';

// the android app of the notes to download, without a session
export const notesDownloadRoute: Route = {
  path: 'notes/download/app',
  loadComponent: () => import('@sak/core/app-download').then((m) => m.AppDownload),
  title: pageTitle('notes.download.page_title'),
  data: { app: 'notes', favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
};

export const notesRoute: Route = {
  path: 'notes',
  loadComponent: () => import('./notes-page').then((m) => m.NotesPage),
  canActivate: [authGuard],
  title: pageTitle('app.notes'),
  data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
};
