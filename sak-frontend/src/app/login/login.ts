import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { appPath } from '../apps';
import { AuthService, LoginMethods } from '../auth/auth.service';

type State = 'idle' | 'sending' | 'sent' | 'wrong-password' | 'rate-limited' | 'failed';

// the login of the apps: with a link by e-mail (the link leads to the frontend's signin page) when a
// mail server is configured, else with the admin password; with both, the e-mail login is shown and
// a link leads to the password login
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          <h1 class="h4 mb-3">Sign in</h1>
          @if (methods() === null) {
            @if (state() === 'failed') {
              <p class="alert alert-danger mb-0" role="alert">
                The sign in could not be loaded, please try again.
              </p>
            } @else {
              <p class="text-body-secondary mb-0" role="status">Loading…</p>
            }
          } @else if (state() === 'sent') {
            <p class="mb-0" role="status" data-testid="login-sent">
              If <strong>{{ email.value }}</strong> may sign in, a link is on its way. Open it in
              this browser to continue.
            </p>
          } @else if (withPassword()) {
            <form [formGroup]="passwordForm" (ngSubmit)="signInWithPassword()">
              <label class="form-label" for="password">Admin password</label>
              <input
                class="form-control mb-3"
                id="password"
                type="password"
                autocomplete="current-password"
                formControlName="password"
                required
              />
              <button
                class="btn btn-primary w-100"
                type="submit"
                [disabled]="password.invalid || state() === 'sending'"
              >
                {{ state() === 'sending' ? 'Signing in…' : 'Sign in' }}
              </button>
            </form>
            @if (methods()!.email) {
              <p class="mt-3 mb-0 text-center">
                <a
                  href=""
                  (click)="$event.preventDefault(); usePassword(false)"
                  data-testid="use-email"
                >
                  Sign in with an e-mail link
                </a>
              </p>
            }
          } @else {
            <form [formGroup]="emailForm" (ngSubmit)="requestLink()">
              <label class="form-label" for="email">E-mail address</label>
              <input
                class="form-control mb-3"
                id="email"
                type="email"
                autocomplete="email"
                formControlName="email"
                required
              />
              <button
                class="btn btn-primary w-100"
                type="submit"
                [disabled]="email.invalid || state() === 'sending'"
              >
                {{ state() === 'sending' ? 'Sending…' : 'Send login link' }}
              </button>
            </form>
            @if (methods()!.password) {
              <p class="mt-3 mb-0 text-center">
                <a
                  href=""
                  (click)="$event.preventDefault(); usePassword(true)"
                  data-testid="use-password"
                >
                  Sign in with password
                </a>
              </p>
            }
          }
          @if (methods() !== null && state() === 'wrong-password') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">The password is not right.</p>
          }
          @if (state() === 'rate-limited') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">
              Too many login attempts, please try again later.
            </p>
          }
          @if (methods() !== null && state() === 'failed') {
            <p class="alert alert-danger mt-3 mb-0" role="alert">
              The sign in did not work, please try again.
            </p>
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

  protected readonly email = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.email],
  });
  protected readonly emailForm = new FormGroup({ email: this.email });

  protected readonly password = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly passwordForm = new FormGroup({ password: this.password });

  // what the backend offers, null until it answered
  protected readonly methods = signal<LoginMethods | null>(null);
  // the password login is shown: chosen, or the only one there is
  protected readonly withPassword = signal(false);
  protected readonly state = signal<State>('idle');

  constructor() {
    this.auth
      .loginMethods()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (methods) => {
          this.methods.set(methods);
          this.withPassword.set(!methods.email && methods.password);
        },
        error: () => this.state.set('failed'),
      });
  }

  protected usePassword(withPassword: boolean): void {
    this.withPassword.set(withPassword);
    this.state.set('idle');
  }

  protected requestLink(): void {
    if (this.email.invalid) {
      return;
    }
    this.state.set('sending');
    this.auth.requestLogin(this.email.value, this.redirect).subscribe({
      next: () => this.state.set('sent'),
      error: (error: unknown) => this.state.set(this.failure(error)),
    });
  }

  protected signInWithPassword(): void {
    if (this.password.invalid) {
      return;
    }
    this.state.set('sending');
    this.auth.signInWithPassword(this.password.value, this.redirect).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect),
      error: (error: unknown) => {
        this.password.reset();
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
