import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { Home } from './home/home';
import { Login } from './login/login';
import { NotesPage } from './notes/notes-page';
import { Signin } from './signin/signin';

// every app needs a session, without one the login is shown and leads back afterwards
export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: 'Apps' },
  { path: 'notes', component: NotesPage, canActivate: [authGuard], title: 'Notes' },
  { path: 'login', component: Login, title: 'Sign in' },
  { path: 'signin', component: Signin, title: 'Signing in' },
  { path: '**', redirectTo: '' },
];
