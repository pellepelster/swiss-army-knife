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
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          <h1 class="h4 mb-3">Sign in</h1>
          @if (state() === 'sent') {
            <p class="mb-0" role="status" data-testid="login-sent">
              If <strong>{{ email.value }}</strong> may sign in, a link is on its way. Open it in
              this browser to continue.
            </p>
          } @else {
            <form [formGroup]="form" (ngSubmit)="submit()">
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
            @if (state() === 'rate-limited') {
              <p class="alert alert-danger mt-3 mb-0" role="alert">
                Too many login attempts, please try again later.
              </p>
            }
            @if (state() === 'failed') {
              <p class="alert alert-danger mt-3 mb-0" role="alert">
                The login link could not be requested, please try again.
              </p>
            }
          }
        </div>
      </main>
    </div>
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
