import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';

interface Session {
  token: string;
  email: string;
  expiresAt: string;
  // what the address may do, e.g. admin
  roles: string[];
}

interface ExchangeResponse {
  sessionToken: string;
  email: string;
  expiresAt: string;
  roles: string[];
  redirect: string;
}

// how a user can sign in: with a link by e-mail, with the admin password, or both
export interface LoginMethods {
  email: boolean;
  password: boolean;
}

// who the backend takes the session for
export interface WhoAmI {
  email: string;
}

export const SESSION_STORAGE_KEY = 'sak.session';

// the api answers the login below this without a session, every other /api/v1 request needs one
export const PUBLIC_API_PREFIX = '/api/v1/auth/login/';

// only paths below the frontend are followed after the login, the backend enforces the same
export function safeRedirect(redirect: string | null | undefined): string {
  if (
    !redirect ||
    redirect.length > 2048 ||
    !redirect.startsWith('/') ||
    redirect.startsWith('//')
  ) {
    return '/';
  }
  if (/[\\\s\u0000-\u001f\u007f]/.test(redirect)) {
    return '/';
  }
  return redirect;
}

// the session lives in localStorage: the installed app starts without the network and the swagger
// page takes the token from there, the content security policy (script-src 'self', object-src
// 'none') keeps other scripts from reading it. What is stored besides the token (its expiry, the
// roles) can be changed by anybody with the browser and only tells whether the token is worth
// sending; nothing is shown or allowed by it, the backend decides with every request
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly session = signal<Session | null>(loadSession());

  // null when signed out or the session expired
  token(): string | null {
    const session = this.session();
    if (!session || Date.parse(session.expiresAt) <= Date.now()) {
      return null;
    }
    return session.token;
  }

  isLoggedIn(): boolean {
    return this.token() !== null;
  }

  // answered the same way for every address, a link is only sent to allowed ones
  requestLogin(email: string, redirect: string | null): Observable<void> {
    return this.http
      .post('/api/v1/auth/login/link', { email, redirect: safeRedirect(redirect) })
      .pipe(map(() => undefined));
  }

  // what the login page offers: the e-mail login only with a mail server, the password login only
  // with an admin password
  loginMethods(): Observable<LoginMethods> {
    return this.http.get<LoginMethods>('/api/v1/auth/login/methods');
  }

  // trades the login token from the e-mailed link for a session, emits where to go next
  signIn(loginToken: string): Observable<string> {
    return this.start(
      this.http.post<ExchangeResponse>('/api/v1/auth/login/exchange', { token: loginToken }),
    );
  }

  // the admin signs in with the admin password, emits where to go next
  signInWithPassword(password: string, redirect: string | null): Observable<string> {
    return this.start(
      this.http.post<ExchangeResponse>('/api/v1/auth/login/password', {
        password,
        redirect: safeRedirect(redirect),
      }),
    );
  }

  // a session the backend answered: stored, then where to go next
  private start(login: Observable<ExchangeResponse>): Observable<string> {
    return login.pipe(
      tap((response) =>
        this.store({
          token: response.sessionToken,
          email: response.email,
          expiresAt: response.expiresAt,
          roles: response.roles,
        }),
      ),
      map((response) => safeRedirect(response.redirect)),
    );
  }

  whoAmI(): Observable<WhoAmI> {
    return this.http.get<WhoAmI>('/api/v1/auth/who-am-i');
  }

  logout(): void {
    this.store(null);
  }

  // the backend rejected the session (401): it ends and the login leads back to the page; a page
  // asks for several things at once, only the first rejection leads to the login
  sessionRejected(): void {
    if (!this.token()) return;
    this.logout();
    void this.router.navigate(['/login'], { queryParams: { redirect: this.router.url } });
  }

  private store(session: Session | null): void {
    this.session.set(session);
    try {
      if (session) {
        localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
      } else {
        localStorage.removeItem(SESSION_STORAGE_KEY);
      }
    } catch {
      // without storage the session only lasts until the page is reloaded
    }
  }
}

function loadSession(): Session | null {
  try {
    const stored = localStorage.getItem(SESSION_STORAGE_KEY);
    const session = stored ? (JSON.parse(stored) as Partial<Session>) : null;
    // an expiry that is no date would count as never expiring
    if (
      typeof session?.token === 'string' &&
      typeof session.email === 'string' &&
      typeof session.expiresAt === 'string' &&
      !Number.isNaN(Date.parse(session.expiresAt))
    ) {
      // sessions stored before there were roles have none, their token is rejected anyway
      const roles = Array.isArray(session.roles)
        ? session.roles.filter((role) => typeof role === 'string')
        : [];
      return { token: session.token, email: session.email, expiresAt: session.expiresAt, roles };
    }
  } catch {
    // unreadable or blocked storage is treated as signed out
  }
  return null;
}
