import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../auth/auth.service';

type State = 'idle' | 'sending' | 'sent' | 'rate-limited' | 'failed';

// the e-mail login of the apps, the link leads to the frontend's signin page
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  styleUrl: '../auth/auth-page.scss',
  template: `
    <main class="auth-card">
      <h1 class="auth-title">Sign in</h1>
      @if (state() === 'sent') {
        <p class="auth-message" role="status" data-testid="login-sent">
          If <strong>{{ email.value }}</strong> may sign in, a link is on its way. Open it in this
          browser to continue.
        </p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()">
          <label class="auth-label" for="email">E-mail address</label>
          <input
            class="auth-input"
            id="email"
            type="email"
            autocomplete="email"
            formControlName="email"
            required
          />
          <button
            class="auth-button"
            type="submit"
            [disabled]="email.invalid || state() === 'sending'"
          >
            {{ state() === 'sending' ? 'Sending…' : 'Send login link' }}
          </button>
        </form>
        @if (state() === 'rate-limited') {
          <p class="auth-message auth-error" role="alert">
            Too many login attempts, please try again later.
          </p>
        }
        @if (state() === 'failed') {
          <p class="auth-message auth-error" role="alert">
            The login link could not be requested, please try again.
          </p>
        }
      }
    </main>
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly redirect = inject(ActivatedRoute).snapshot.queryParamMap.get('redirect');

  protected readonly email = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.email],
  });

  protected readonly form = new FormGroup({ email: this.email });

  protected readonly state = signal<State>('idle');

  protected submit(): void {
    if (this.email.invalid) {
      return;
    }
    this.state.set('sending');
    this.auth.requestLogin(this.email.value, this.redirect).subscribe({
      next: () => this.state.set('sent'),
      error: (error: unknown) =>
        this.state.set(
          error instanceof HttpErrorResponse && error.status === 429 ? 'rate-limited' : 'failed',
        ),
    });
  }
}
