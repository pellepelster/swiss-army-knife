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

// the address this browser asked a login link for, and when (SEC-7)
export const LOGIN_REQUEST_KEY = 'sak.login.request';

// as long as a login link lasts (DEFAULT_LOGIN_TOKEN_TTL of sak-auth)
const LOGIN_LINK_MS = 15 * 60 * 1000;

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

  // answered the same way for every address, a link is only sent to allowed ones. This browser
  // remembers the address it asked for: its link signs in at once, any other asks first (SEC-7)
  requestLogin(email: string, redirect: string | null): Observable<void> {
    return this.http
      .post('/api/v1/auth/login/link', { email, redirect: safeRedirect(redirect) })
      .pipe(
        tap(() =>
          writeStorage(
            LOGIN_REQUEST_KEY,
            JSON.stringify({ email: sameAddress(email), at: Date.now() }),
          ),
        ),
        map(() => undefined),
      );
  }

  // whether this browser asked for a link for the address within the time a link lasts (SEC-7)
  askedFor(email: string): boolean {
    try {
      const asked = JSON.parse(readStorage(LOGIN_REQUEST_KEY) ?? 'null') as {
        email?: unknown;
        at?: unknown;
      } | null;
      return (
        typeof asked?.email === 'string' &&
        typeof asked.at === 'number' &&
        sameAddress(asked.email) === sameAddress(email) &&
        Date.now() - asked.at < LOGIN_LINK_MS
      );
    } catch {
      return false;
    }
  }

  // the address signed in now, null without a session
  signedInAs(): string | null {
    return this.token() === null ? null : (this.session()?.email ?? null);
  }

  // the sign-in follows the user mode of the deployment (UX-7)
  userMode(): Observable<UserMode> {
    return this.info().pipe(map((info) => info.userMode));
  }

  // the deployment that answers, without a session
  info(): Observable<DeploymentInfo> {
    return this.http.get<DeploymentInfo>('/api/v1/info');
  }

  // trades the login token from the e-mailed link for a session, emits where to go next; the
  // request of this browser is used up
  signIn(loginToken: string): Observable<string> {
    return this.start(
      this.http.post<ExchangeResponse>('/api/v1/auth/login/exchange', { token: loginToken }),
    ).pipe(tap(() => removeStorage(LOGIN_REQUEST_KEY)));
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
  // Date header tells the time of the backend its expiry is in; a desktop app binds the token to
  // the pkce challenge of its verifier
  connect(challenge?: string): Observable<HttpResponse<Connect>> {
    return this.http.post<Connect>('/api/v1/auth/connect', challenge ? { challenge } : {}, {
      observe: 'response',
    });
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

// an address as the backend compares it
function sameAddress(email: string): string {
  return email.trim().toLowerCase();
}

// the address a login token is for (its subject), null when it is no token; only shown, the
// backend checks the token at the exchange, so a forged one signs nobody in (SEC-7)
export function loginTokenEmail(token: string): string | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const subject = (JSON.parse(json) as { sub?: unknown }).sub;
    return typeof subject === 'string' && subject.length > 0 ? subject : null;
  } catch {
    return null;
  }
}

// localStorage can be missing or refuse, e.g. in a private window: the login works without it
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // the link then asks before it signs in
  }
}

function removeStorage(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // nothing kept
  }
}
