import { HttpClient, HttpResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';
import {
  Connect,
  ConnectStatus,
  DeploymentInfo,
  ExchangeResponse,
  UserMode,
  WhoAmI,
} from '../api/models';

interface Session {
  token: string;
  email: string;
  expiresAt: string;
  // what the address may do, e.g. admin
  roles: string[];
}

// DeploymentInfo: no version for a build that is no release. WhoAmI: admin for the password login,
// else the e-mail address, and the name an admin gave the user
export type { DeploymentInfo, UserMode, WhoAmI } from '../api/models';

export const SESSION_STORAGE_KEY = 'sak.session';

// loads the url as a new page, so nothing the app kept of the user before (stores, thumbnails)
// shows to the next user of the tab
export const LEAVE_PAGE = new InjectionToken<(url: string) => void>('LEAVE_PAGE', {
  factory: () => {
    const document = inject(DOCUMENT);
    return (url: string) => document.location.assign(url);
  },
});

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

// the session is in localStorage: the installed app starts offline and the swagger page reads the
// token there; the content security policy keeps other scripts out. The expiry and the roles can
// be changed in the browser, so they only tell whether the token is worth sending; the backend
// decides with every request
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly leavePage = inject(LEAVE_PAGE);

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

  // the sign-in follows the user mode of the deployment (UX-7)
  userMode(): Observable<UserMode> {
    return this.info().pipe(map((info) => info.userMode));
  }

  // the deployment that answers, without a session
  info(): Observable<DeploymentInfo> {
    return this.http.get<DeploymentInfo>('/api/v1/info');
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

  // a QR code that connects an app as the signed in user (SECURITY-3), with the response, whose
  // Date header tells the time of the backend its expiry is in
  connect(): Observable<HttpResponse<Connect>> {
    return this.http.post<Connect>('/api/v1/auth/connect', null, { observe: 'response' });
  }

  // whether an app used the code; 404 once it expired
  connectStatus(id: string): Observable<ConnectStatus> {
    return this.http.get<ConnectStatus>(`/api/v1/auth/connect/${encodeURIComponent(id)}`);
  }

  logout(): void {
    this.store(null);
  }

  // the login opens as a new page, see LEAVE_PAGE
  signOut(): void {
    this.logout();
    this.leavePage('/login');
  }

  // a 401: the login opens as a new page and leads back here. A page sends several requests at once,
  // only the first rejection acts
  sessionRejected(): void {
    if (!this.token()) return;
    this.logout();
    this.leavePage(`/login?redirect=${encodeURIComponent(this.router.url)}`);
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
      // a session without roles has a token the backend rejects anyway
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
