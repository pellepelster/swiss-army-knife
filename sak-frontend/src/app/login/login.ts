import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { appPath } from '../apps';
import { AuthService, UserMode } from '../auth/auth.service';
import { TranslatePipe } from '../i18n/i18n';
import { validLoginEmail } from '../shared/email';

type State = 'idle' | 'sending' | 'sent' | 'wrong-password' | 'rate-limited' | 'failed';

// the login of the apps, by the user mode of the deployment (UX-7): in the multi user mode with a
// link by e-mail (the link leads to the frontend's signin page) and a link to the admin password
// login, in the single user mode only with the admin password. The fields are signals, as in the other
// forms: @angular/forms would be in the bundle every page loads at the start for this one (TD-5)
@Component({
  selector: 'app-login',
  imports: [TranslatePipe],
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          <h1 class="h4 mb-3">{{ 'login.sign_in' | t }}</h1>
          @if (userMode() === null) {
            @if (state() === 'failed') {
              <p class="alert alert-danger mb-0" role="alert">{{ 'login.not_loaded' | t }}</p>
            } @else {
              <p class="text-body-secondary mb-0" role="status">{{ 'shared.loading' | t }}</p>
            }
          } @else if (state() === 'sent') {
            <p class="mb-0" role="status" data-testid="login-sent">
              {{ 'login.sent_before' | t }} <strong>{{ email() }}</strong>
              {{ 'login.sent_after' | t }}
            </p>
          } @else if (withPassword()) {
            <form (submit)="$event.preventDefault(); signInWithPassword()">
              <label class="form-label" for="password">{{ 'login.admin_password' | t }}</label>
              <input
                class="form-control mb-3"
                id="password"
                type="password"
                autocomplete="current-password"
                required
                #passwordField
                [value]="password()"
                (input)="password.set(passwordField.value)"
              />
              <button
                class="btn btn-primary w-100"
                type="submit"
                [disabled]="!password() || state() === 'sending'"
              >
                {{ (state() === 'sending' ? 'login.signing_in' : 'login.sign_in') | t }}
              </button>
            </form>
            @if (userMode() === 'multi') {
              <p class="mt-3 mb-0 text-center">
                <a
                  href=""
                  (click)="$event.preventDefault(); usePassword(false)"
                  data-testid="use-email"
                >
                  {{ 'login.use_email' | t }}
                </a>
              </p>
            }
          } @else {
            <form (submit)="$event.preventDefault(); requestLink()">
              <label class="form-label" for="email">{{ 'shared.email' | t }}</label>
              <input
                class="form-control mb-3"
                id="email"
                type="email"
                autocomplete="email"
                required
                #emailField
                [value]="email()"
                (input)="email.set(emailField.value)"
              />
              <button
                class="btn btn-primary w-100"
                type="submit"
                [disabled]="!emailValid() || state() === 'sending'"
              >
                {{ (state() === 'sending' ? 'login.sending' : 'login.send_link') | t }}
              </button>
            </form>
            <p class="mt-3 mb-0 text-center">
              <a
                href=""
                (click)="$event.preventDefault(); usePassword(true)"
                data-testid="use-password"
              >
                {{ 'login.use_password' | t }}
              </a>
            </p>
          }
          @if (userMode() !== null && state() === 'wrong-password') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">
              {{ 'login.wrong_password' | t }}
            </p>
          }
          @if (state() === 'rate-limited') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">{{ 'login.rate_limited' | t }}</p>
          }
          @if (userMode() !== null && state() === 'failed') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">{{ 'login.failed' | t }}</p>
          }
        </div>
      </main>
    </div>
  `,
})
export class Login {
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;

  // where to go after signing in: the page the login started from, else the app of the app
  // parameter, a short form for links and bookmarks (/login?app=grocery); else the start page
  private readonly redirect = this.params.get('redirect') ?? appPath(this.params.get('app'));

  protected readonly email = signal('');
  protected readonly emailValid = computed(() => validLoginEmail(this.email()));

  protected readonly password = signal('');

  // the user mode of the deployment, null until the backend answered
  protected readonly userMode = signal<UserMode | null>(null);
  // the password login is shown: chosen, or the only one in the single user mode
  protected readonly withPassword = signal(false);
  protected readonly state = signal<State>('idle');

  constructor() {
    this.auth
      .userMode()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (userMode) => {
          this.userMode.set(userMode);
          this.withPassword.set(userMode === 'single');
        },
        error: () => this.state.set('failed'),
      });
  }

  protected usePassword(withPassword: boolean): void {
    this.withPassword.set(withPassword);
    this.state.set('idle');
  }

  protected requestLink(): void {
    if (!this.emailValid()) {
      return;
    }
    this.state.set('sending');
    this.auth.requestLogin(this.email().trim(), this.redirect).subscribe({
      next: () => this.state.set('sent'),
      error: (error: unknown) => this.state.set(this.failure(error)),
    });
  }

  protected signInWithPassword(): void {
    if (!this.password()) {
      return;
    }
    this.state.set('sending');
    this.auth.signInWithPassword(this.password(), this.redirect).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect),
      error: (error: unknown) => {
        this.password.set('');
        this.state.set(
          error instanceof HttpErrorResponse && error.status === 401
            ? 'wrong-password'
            : this.failure(error),
        );
      },
    });
  }

  private failure(error: unknown): State {
    return error instanceof HttpErrorResponse && error.status === 429 ? 'rate-limited' : 'failed';
  }
}
