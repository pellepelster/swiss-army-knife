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

type State =
  | { readonly kind: 'asking' }
  | { readonly kind: 'connecting' }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'failed'; readonly error: TextKey };

// /connect-desktop: a desktop app on this computer asks for a session of the signed in user
// (SECURITY-3). Only after the user confirms, the page gets a connect token bound to the challenge
// of the app and sends the browser with it to the loopback port of the app. The token is never
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
                [disabled]="current.kind === 'connecting'"
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

  constructor() {
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
