import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { Home } from './home/home';
import { hostingRoute } from './hosting/hosting.routes';
import { Login } from './login/login';
import { NotesPage } from './notes/notes-page';
import { Signin } from './signin/signin';

// every app needs a session, without one the login is shown and leads back afterwards; an app
// names its favicon in its data, the others show the swiss army knife (FaviconService)
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: 'Apps' },
  {
    path: 'notes',
    component: NotesPage,
    canActivate: [authGuard],
    title: 'Notes',
    data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
  },
  hostingRoute,
  { path: 'login', component: Login, title: 'Sign in' },
  { path: 'signin', component: Signin, title: 'Signing in' },
  { path: '**', redirectTo: '' },
];
