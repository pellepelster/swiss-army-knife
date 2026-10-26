import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { Home } from './home/home';
import { Login } from './login/login';
import { Signin } from './signin/signin';

export const routes: Routes = [
  { path: '', component: Home, canActivate: [authGuard], title: 'sak console' },
  { path: 'login', component: Login, title: 'Sign in · sak console' },
  { path: 'signin', component: Signin, title: 'Signing in · sak console' },
  { path: '**', redirectTo: '' },
];
