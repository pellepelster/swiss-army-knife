import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

export interface Session {
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

export interface Me {
  email: string;
  expiresAt: string;
  roles: string[];
}

export const SESSION_STORAGE_KEY = 'sak.session';

// the api answers login and exchange without a session, every other /api/v1 request needs one
export const PUBLIC_API_URLS = ['/api/v1/auth/login', '/api/v1/auth/exchange'];

// only paths below the console are followed after the login, the backend enforces the same
export function safeRedirect(redirect: string | null | undefined): string {
  if (!redirect || redirect.length > 2048 || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return '/';
  }
  if (/[\\\s\u0000-\u001f\u007f]/.test(redirect)) {
    return '/';
  }
  return redirect;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly session = signal<Session | null>(loadSession());

  readonly email = computed(() => this.session()?.email ?? null);

  readonly expiresAt = computed(() => this.session()?.expiresAt ?? null);

  readonly roles = computed(() => this.session()?.roles ?? []);

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
      .post('/api/v1/auth/login', { email, redirect: safeRedirect(redirect) })
      .pipe(map(() => undefined));
  }

  // trades the login token from the e-mailed link for a session, emits where to go next
  signIn(loginToken: string): Observable<string> {
    return this.http.post<ExchangeResponse>('/api/v1/auth/exchange', { token: loginToken }).pipe(
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

  me(): Observable<Me> {
    return this.http.get<Me>('/api/v1/auth/me');
  }

  logout(): void {
    this.store(null);
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
      const roles = Array.isArray(session.roles) ? session.roles.filter((role) => typeof role === 'string') : [];
      return { token: session.token, email: session.email, expiresAt: session.expiresAt, roles };
    }
  } catch {
    // unreadable or blocked storage is treated as signed out
  }
  return null;
}
