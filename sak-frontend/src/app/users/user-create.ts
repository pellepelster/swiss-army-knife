import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { validEmail } from '../shared/email';
import { UsersService } from './users.service';
import { PageHeader } from '../shared/page-header';

// the longest name of a user (UserService.MAX_NAME_LENGTH)
const MAX_NAME_LENGTH = 64;

// adds a user by their address, with an optional name, on a page of its own; afterwards the users
// show. The address is checked when it loses focus (UX-6); no mail is sent, the user requests a
// link on the login page
@Component({
  selector: 'app-user-create',
  imports: [PageHeader, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/users" [backLabel]="'users.all_users' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'users.create_user' | t }}</h1>
    </header>

    <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); add()">
      <div>
        <label class="form-label" for="user-email">{{ 'shared.email' | t }}</label>
        <input
          #emailField
          class="form-control"
          id="user-email"
          type="email"
          autocomplete="off"
          autocapitalize="off"
          maxlength="254"
          aria-describedby="user-email-hint user-email-error"
          [attr.aria-invalid]="emailInvalid()"
          [class.is-invalid]="emailInvalid()"
          [value]="email()"
          (input)="email.set(emailField.value); emailLeft.set(false)"
          (blur)="emailLeft.set(true)"
        />
        @if (emailInvalid()) {
          <div class="invalid-feedback" id="user-email-error">
            {{ 'users.invalid_email' | t }}
          </div>
        }
        <span class="form-text" id="user-email-hint">{{ 'users.email_hint' | t }}</span>
      </div>
      <div>
        <label class="form-label" for="user-name">{{ 'users.name' | t }}</label>
        <input
          #nameField
          class="form-control"
          id="user-name"
          autocomplete="off"
          [attr.maxlength]="maxNameLength"
          [value]="name()"
          (input)="name.set(nameField.value)"
        />
        <span class="form-text">{{ 'users.name_hint' | t }}</span>
      </div>

      @if (addError(); as error) {
        <p class="alert alert-danger mb-0" role="alert" data-testid="add-error">{{ error }}</p>
      }

      <!-- cancel and add are one group (UX-9) -->
      <div class="d-flex justify-content-end">
        <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
          <a class="btn btn-outline-secondary" routerLink="/users">{{ 'shared.cancel' | t }}</a>
          <button class="btn btn-primary" type="submit" [disabled]="!valid() || adding()">
            @if (adding()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="sak-icon-plus" aria-hidden="true"></i>
            }
            {{ 'users.create_user' | t }}
          </button>
        </div>
      </div>
    </form>
  `,
})
export class UserCreate {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(UsersService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  protected readonly maxNameLength = MAX_NAME_LENGTH;
  protected readonly email = signal('');
  protected readonly name = signal('');
  // the address was left since it was last typed in, its error shows (UX-6)
  protected readonly emailLeft = signal(false);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);

  protected readonly valid = computed(() => validEmail(this.email()));
  protected readonly emailInvalid = computed(
    () => this.emailLeft() && this.email().trim() !== '' && !this.valid(),
  );

  protected add(): void {
    this.emailLeft.set(true);
    if (!this.valid()) return;
    this.adding.set(true);
    this.addError.set(null);
    this.service
      .add(this.email().trim(), this.name().trim() || null)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/users']),
        error: (error: unknown) => {
          this.adding.set(false);
          this.addError.set(
            apiErrorMessage(error, this.i18n.t('users.not_added'), {
              403: this.i18n.t('users.forbidden_add'),
            }),
          );
        },
      });
  }
}
