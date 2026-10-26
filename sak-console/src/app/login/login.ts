import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../auth/auth.service';

type State = 'idle' | 'sending' | 'sent' | 'rate-limited' | 'failed';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  template: `
    <div class="row justify-content-center">
      <div class="col-md-8 col-lg-5">
        <div class="card shadow-sm">
          <div class="card-body p-4">
            <h1 class="h4 mb-3">Sign in</h1>
            @if (state() === 'sent') {
              <div class="alert alert-success mb-0" role="status" data-testid="login-sent">
                If <strong>{{ email.value }}</strong> may sign in, a link is on its way. Open it
                in this browser to continue.
              </div>
            } @else {
              <form [formGroup]="form" (ngSubmit)="submit()">
                <div class="mb-3">
                  <label class="form-label" for="email">E-mail address</label>
                  <input
                    class="form-control"
                    id="email"
                    type="email"
                    autocomplete="email"
                    formControlName="email"
                    required
                  />
                </div>
                <button
                  class="btn btn-primary w-100"
                  type="submit"
                  [disabled]="email.invalid || state() === 'sending'"
                >
                  @if (state() === 'sending') {
                    <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                  }
                  Send login link
                </button>
              </form>
              @if (state() === 'rate-limited') {
                <div class="alert alert-warning mt-3 mb-0" role="alert">
                  Too many login attempts, please try again later.
                </div>
              }
              @if (state() === 'failed') {
                <div class="alert alert-danger mt-3 mb-0" role="alert">
                  The login link could not be requested, please try again.
                </div>
              }
            }
          </div>
        </div>
      </div>
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
