import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { PaginationControl } from '../shared/pagination';
import { pagedList, pageOffset } from '../shared/paged-list';
import { ActionMenu } from '../shared/action-menu';
import { UsersService } from './users.service';
import { PageHeader } from '../shared/page-header';

// who may sign in, a page at a time: the admins of the configuration, then the users an admin
// added. A user is added on its own page and deleted after a confirmation in its place (UX-4.1);
// an admin of the configuration is not deleted
@Component({
  selector: 'app-user-list',
  imports: [PageHeader, PaginationControl, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'users.title' | t }}</h1>
      <a class="btn btn-primary" routerLink="/users/create" [title]="'users.create_user' | t">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'users.create_user' | t }}
      </a>
    </header>

    <p class="text-body-secondary">{{ 'users.intro' | t }}</p>

    @if (list.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    <!-- the links to the android apps were sent -->
    @if (sent(); as sent) {
      <p class="alert alert-success" role="status" data-testid="app-link-sent">{{ sent }}</p>
    }

    @if (list.loaded()) {
      <div class="card table-responsive">
        <table class="table align-middle mb-0" [attr.aria-label]="'users.title' | t">
          <thead>
            <tr>
              <th scope="col">{{ 'users.column.email' | t }}</th>
              <th scope="col">{{ 'shared.name' | t }}</th>
              <th scope="col">{{ 'users.column.role' | t }}</th>
              <th scope="col" class="text-end">{{ 'shared.actions' | t }}</th>
            </tr>
          </thead>
          <tbody>
            @for (user of list.items(); track user.email) {
              <tr [attr.data-testid]="'user-' + user.email">
                <td class="text-break" data-testid="email">{{ user.email }}</td>
                <td class="text-break" data-testid="name">
                  @if (user.name) {
                    {{ user.name }}
                  } @else {
                    <span class="text-body-secondary" [attr.aria-label]="'users.no_name' | t"
                      >–</span
                    >
                  }
                </td>
                <td>
                  <span class="badge border text-body-secondary fw-normal" data-testid="role">{{
                    (user.configured ? 'users.role.admin' : 'users.role.user') | t
                  }}</span>
                </td>
                <td class="text-end">
                  <!-- an admin of the configuration is not deleted here (UX-8) -->
                  <app-action-menu
                    [name]="user.email"
                    deletes
                    [deleteDisabled]="user.configured || user.id == null ? configured : null"
                    (deleted)="user.id != null && delete(user.id)"
                  >
                    <button
                      type="button"
                      class="dropdown-item"
                      role="menuitem"
                      [disabled]="sending()"
                      (click)="sendAppLink(user.email)"
                    >
                      <i class="fa-solid fa-mobile-screen me-2" aria-hidden="true"></i
                      >{{ 'users.send_app_link' | t }}
                    </button>
                  </app-action-menu>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <app-pagination
        [label]="'users.pages' | t"
        [pagination]="list.pagination()"
        (offsetChange)="list.goTo($event)"
      />
    } @else if (!list.error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class UserList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(UsersService);
  private readonly i18n = inject(I18n);

  // the offset query parameter
  readonly offset = input(0, { transform: pageOffset });

  protected readonly list = pagedList({
    path: '/users',
    offset: this.offset,
    page: (offset) => this.service.page(offset),
    error: (error) =>
      apiErrorMessage(error, this.i18n.t('users.not_loaded'), {
        403: this.i18n.t('users.forbidden_see'),
      }),
  });
  // why an admin is not deleted
  protected readonly configured = this.i18n.t('users.configured');

  // the links to the android apps on their way, and to whom they went last
  protected readonly sending = signal(false);
  protected readonly sent = signal<string | null>(null);

  protected sendAppLink(email: string): void {
    this.sending.set(true);
    this.sent.set(null);
    this.list.error.set(null);
    this.service
      .sendAppLink(email)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.sending.set(false);
          this.sent.set(this.i18n.t('users.app_link_sent', { email }));
        },
        error: (error: unknown) => {
          this.sending.set(false);
          this.list.error.set(apiErrorMessage(error, this.i18n.t('users.app_link_not_sent')));
        },
      });
  }

  protected delete(id: number): void {
    this.service
      .delete(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.list.reload(),
        error: (error: unknown) =>
          this.list.error.set(apiErrorMessage(error, this.i18n.t('users.not_deleted'))),
      });
  }
}
