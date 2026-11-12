import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { generate } from 'lean-qr';
import { catchError, exhaustMap, map, merge, of, Subscription, timer } from 'rxjs';
import { Connect } from '../api/models';
import { AuthService } from '../auth/auth.service';
import { TextKey, TranslatePipe } from '../i18n/i18n';
import { PageHeader } from '../shared/page-header';

// how often the page asks whether an app used the code
export const POLL_MS = 2_000;
// the light modules around the code a scanner needs to find it, in modules
export const QUIET_ZONE = 4;

// a code on the page: the modules of the qr code of its link as an svg path, never the link itself
interface Code {
  readonly id: string;
  // the modules of the code with its quiet zone
  readonly modules: number;
  readonly path: string;
  // when it expires, in the time of the browser
  readonly deadline: number;
}

type State =
  | { readonly kind: 'loading' }
  | { readonly kind: 'shown'; readonly code: Code }
  | { readonly kind: 'expired' }
  | { readonly kind: 'connected' }
  | { readonly kind: 'failed'; readonly error: TextKey };

// what the watch of a shown code learns: a second passed, or the answer of the backend
type Watch = 'tick' | 'used' | 'unused' | 'gone';

// /connect-app: a QR code that connects an android app as the signed in user (SECURITY-3). The code
// is asked for when the page opens, never before, shows until it expires or an app used it and is
// never renewed on its own; its link holds the token, so it is neither shown nor logged, and leaving
// the page forgets it. The modules are dark on light in both color schemes, a scanner needs that
@Component({
  selector: 'app-connect-page',
  imports: [PageHeader, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  styles: `
    .qr {
      width: min(100%, 20rem);
      height: auto;
      background: var(--sak-terminal-ink);
    }
    .qr path {
      fill: var(--sak-terminal);
    }
  `,
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1">{{ 'connect.title' | t }}</h1>
      <button
        type="button"
        class="btn btn-primary"
        [disabled]="state().kind === 'loading'"
        (click)="request()"
      >
        <i class="fa-solid fa-qrcode" aria-hidden="true"></i> {{ 'connect.another' | t }}
      </button>
    </header>

    @let current = state();
    @if (current.kind === 'failed') {
      <!-- the error of the action of the page, next to its toolbar (UX-15) -->
      <p class="alert alert-danger" role="alert" data-testid="error">{{ current.error | t }}</p>
    }

    <p>{{ 'connect.instructions' | t }}</p>
    <p class="alert alert-warning" data-testid="warning">
      <i class="fa-solid fa-triangle-exclamation me-2" aria-hidden="true"></i
      >{{ 'connect.warning' | t }}
    </p>

    <div class="vstack align-items-center gap-2" aria-live="polite">
      @if (current.kind === 'loading') {
        <p class="mb-0">{{ 'shared.loading' | t }}</p>
      } @else if (current.kind === 'shown') {
        <svg
          class="qr rounded"
          role="img"
          data-testid="qr"
          [attr.aria-label]="'connect.code' | t"
          [attr.viewBox]="'0 0 ' + current.code.modules + ' ' + current.code.modules"
          shape-rendering="crispEdges"
        >
          <path [attr.d]="current.code.path" />
        </svg>
        <p class="mb-0 text-body-secondary" data-testid="countdown">
          {{ 'connect.valid_for' | t: { time: remaining() } }}
        </p>
      } @else if (current.kind === 'expired') {
        <p class="mb-0" data-testid="expired">{{ 'connect.expired' | t }}</p>
      } @else if (current.kind === 'connected') {
        <p class="h4 mb-0" data-testid="connected">
          <i class="fa-solid fa-circle-check text-success me-2" aria-hidden="true"></i
          >{{ 'connect.connected' | t }}
        </p>
        <p class="mb-0">{{ 'connect.connected_text' | t }}</p>
      }
    </div>
  `,
})
export class ConnectPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);

  protected readonly state = signal<State>({ kind: 'loading' });
  private readonly now = signal(Date.now());
  // the time left of the shown code, m:ss
  protected readonly remaining = computed(() => {
    const state = this.state();
    if (state.kind !== 'shown') return '';
    const seconds = Math.max(0, Math.ceil((state.code.deadline - this.now()) / 1000));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  });

  // the request of a code, then its countdown and the polling; a new code replaces both
  private load?: Subscription;

  constructor() {
    this.request();
    // the code is forgotten with the page
    this.destroyRef.onDestroy(() => this.state.set({ kind: 'loading' }));
  }

  protected request(): void {
    this.load?.unsubscribe();
    this.state.set({ kind: 'loading' });
    this.load = this.auth
      .connect()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.show(response),
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

  private show(response: HttpResponse<Connect>): void {
    const connect = response.body!;
    // the expiry is in the time of the backend, the clock of the browser may differ
    const backendNow = Date.parse(response.headers.get('Date') ?? '');
    const skew = Number.isNaN(backendNow) ? 0 : backendNow - Date.now();
    const code: Code = {
      id: connect.id,
      ...qrPath(connect.link),
      deadline: Date.parse(connect.expiresAt) - skew,
    };
    this.now.set(Date.now());
    this.state.set({ kind: 'shown', code });
    this.load = merge(
      timer(1_000, 1_000).pipe(map((): Watch => 'tick')),
      timer(POLL_MS, POLL_MS).pipe(
        exhaustMap(() =>
          this.auth.connectStatus(code.id).pipe(
            map((status): Watch => (status.used ? 'used' : 'unused')),
            // a poll that fails tries again, only a code the backend no longer has is gone
            catchError((error: unknown) =>
              of<Watch>(
                error instanceof HttpErrorResponse && error.status === 404 ? 'gone' : 'unused',
              ),
            ),
          ),
        ),
      ),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((watch) => {
        this.now.set(Date.now());
        if (watch === 'used') this.end({ kind: 'connected' });
        else if (watch === 'gone' || this.now() >= code.deadline) this.end({ kind: 'expired' });
      });
  }

  // the code is done with: no countdown, no polling, the code forgotten
  private end(state: State): void {
    this.load?.unsubscribe();
    this.state.set(state);
  }
}

// the dark modules of the qr code of the text as one svg path, a square of one unit each, inside the
// quiet zone
export function qrPath(text: string): { modules: number; path: string } {
  const qr = generate(text);
  let path = '';
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.get(x, y)) path += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`;
    }
  }
  return { modules: qr.size + 2 * QUIET_ZONE, path };
}
