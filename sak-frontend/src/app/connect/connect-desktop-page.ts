import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Location } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { AuthService, LEAVE_PAGE } from '../auth/auth.service';
import { TextKey, TranslatePipe } from '../i18n/i18n';

// what the desktop app sends: the port it listens on, its state and the pkce challenge (S256)
export interface DesktopRequest {
  readonly port: number;
  readonly state: string;
  readonly challenge: string;
}

// the parameters of the link the desktop app opened, null unless all are well formed; the port is
// the only part of the target the link chooses, the host is always the loopback
export function desktopRequest(params: {
  get(name: string): string | null;
}): DesktopRequest | null {
  const port = params.get('port') ?? '';
  const state = params.get('state') ?? '';
  const challenge = params.get('challenge') ?? '';
  if (!/^[1-9][0-9]{3,4}$/.test(port) || Number(port) > 65535) return null;
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(state)) return null;
  if (!/^[A-Za-z0-9_-]{43}$/.test(challenge)) return null;
  return { port: Number(port), state, challenge };
}

// where the browser hands the token to the desktop app; without a token the app stops waiting
export function desktopCallback(request: DesktopRequest, token: string | null): string {
  const query = new URLSearchParams({ state: request.state });
  if (token === null) query.set('error', 'cancelled');
  else query.set('token', token);
  return `http://127.0.0.1:${request.port}/callback?${query}`;
}

// the code the page and the desktop app both show, so the user sees that the page asks for that
// app and not for another program (SEC-31): 6 characters of the sha-256 of the challenge, none that
// reads as another, as ABC-DEF. `connect_code` of sak-desktop-shared computes the same
export async function connectCode(challenge: string): Promise<string> {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const digest = new Uint8Array(
    await crypto.subtle.digest('SHA-256', new TextEncoder().encode(challenge)),
  );
  const code = [...digest.slice(0, 6)].map((byte) => alphabet[byte & 31]).join('');
  return `${code.slice(0, 3)}-${code.slice(3)}`;
}

type State =
  | { readonly kind: 'asking' }
  | { readonly kind: 'connecting' }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'failed'; readonly error: TextKey };

// /connect-desktop: a desktop app on this computer asks for a session of the signed in user
// (SECURITY-3). Only after the user confirms, the page gets a connect token bound to the challenge
// of the app and sends the browser with it to the loopback port of the app; the code of the
// challenge tells the user which app asks. The token is never
// shown, it leaves the page only in that redirect
@Component({
  selector: 'app-connect-desktop-page',
  imports: [TranslatePipe],
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-10 col-md-8 col-lg-6">
        <div class="card card-body">
          <h1 class="h4 mb-3">{{ 'connect_desktop.title' | t }}</h1>
          @let current = state();
          @if (current.kind === 'invalid') {
            <p class="alert alert-danger mb-0" role="alert" data-testid="error">
              {{ 'connect_desktop.invalid' | t }}
            </p>
          } @else {
            <p>{{ 'connect_desktop.question' | t }}</p>
            <p class="alert alert-warning" data-testid="warning">
              <i class="fa-solid fa-triangle-exclamation me-2" aria-hidden="true"></i
              >{{ 'connect_desktop.warning' | t }}
              <strong
                class="d-block fs-3 font-monospace mt-2"
                data-testid="code"
                [attr.aria-label]="'connect_desktop.code' | t"
                >{{ code() }}</strong
              >
            </p>
            @if (current.kind === 'failed') {
              <p class="alert alert-danger" role="alert" data-testid="error">
                {{ current.error | t }}
              </p>
            }
            <div class="d-flex justify-content-end gap-2">
              <button
                type="button"
                class="btn btn-outline-secondary"
                [disabled]="current.kind === 'connecting'"
                (click)="cancel()"
              >
                {{ 'connect_desktop.cancel' | t }}
              </button>
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="current.kind === 'connecting' || !code()"
                (click)="connect()"
              >
                {{
                  (current.kind === 'connecting'
                    ? 'connect_desktop.connecting'
                    : 'connect_desktop.connect'
                  ) | t
                }}
              </button>
            </div>
          }
        </div>
      </main>
    </div>
  `,
})
export class ConnectDesktopPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);
  private readonly leave = inject(LEAVE_PAGE);
  private readonly request = desktopRequest(inject(ActivatedRoute).snapshot.queryParamMap);

  protected readonly state = signal<State>(this.request ? { kind: 'asking' } : { kind: 'invalid' });

  // shown before the user can connect
  protected readonly code = signal('');

  constructor() {
    if (this.request) {
      void connectCode(this.request.challenge).then((code) => this.code.set(code));
    }
    // the state and the challenge do not stay in the history
    inject(Location).replaceState('/connect-desktop');
  }

  protected connect(): void {
    const request = this.request!;
    this.state.set({ kind: 'connecting' });
    this.auth
      .connect(request.challenge)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const token = new URL(response.body!.link).hash.replace(/^#token=/, '');
          this.leave(desktopCallback(request, token));
        },
        error: (error: unknown) =>
          this.state.set({
            kind: 'failed',
            error:
              error instanceof HttpErrorResponse && error.status === 429
                ? 'connect.too_many'
                : 'connect.failed',
          }),
      });
  }

  protected cancel(): void {
    this.leave(desktopCallback(this.request!, null));
  }
}
