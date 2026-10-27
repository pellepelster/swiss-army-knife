import { Routes } from '@angular/router';
import { Home } from './home/home';
import { NotesPage } from './notes/notes-page';

export const routes: Routes = [
  { path: '', component: Home, title: 'Apps' },
  { path: 'notes', component: NotesPage, title: 'Notes' },
  { path: '**', redirectTo: '' },
];
