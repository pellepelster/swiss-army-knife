import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage, lastPageOffset, Pagination } from '../shared/api';
import { PaginationControl } from '../shared/pagination';
import { ActionMenu } from '../shared/action-menu';
import { User, UsersService } from './users.service';

// who may sign in, a page at a time, as a table of the address, the name, the role and the
// actions: the admins of the configuration of the deployment, then the users an admin added. A user is added on a page of its own
// and deleted after a confirmation in its place (UX-4.1); an admin of the configuration is not
@Component({
  selector: 'app-user-list',
  imports: [PaginationControl, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        [attr.aria-label]="'shared.all_apps' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'users.title' | t }}</h1>
      <a class="btn btn-primary" routerLink="/users/create" [title]="'users.add_user' | t">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'shared.add' | t }}
      </a>
    </header>

    <p class="text-body-secondary">{{ 'users.intro' | t }}</p>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <div class="card table-responsive">
        <table class="table align-middle mb-0" [attr.aria-label]="'users.title' | t">
          <thead>
            <tr>
              <th scope="col">{{ 'users.column.email' | t }}</th>
              <th scope="col">{{ 'shared.name' | t }}</th>
              <th scope="col">{{ 'users.column.role' | t }}</th>
              <th scope="col" class="text-end">{{ 'users.column.actions' | t }}</th>
            </tr>
          </thead>
          <tbody>
            @for (user of users(); track user.email) {
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
                  <!-- an admin comes from the configuration, it is not deleted here (UX-8) -->
                  <app-action-menu
                    [name]="user.email"
                    [question]="'users.confirm_delete' | t: { name: user.name ?? user.email }"
                    [deleteDisabled]="user.configured || user.id === null ? configured : null"
                    (deleted)="user.id !== null && delete(user.id)"
                  />
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <app-pagination
        [label]="'users.pages' | t"
        [pagination]="pagination()"
        (offsetChange)="goTo($event)"
      />
    } @else if (!error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class UserList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(UsersService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route's offset query parameter, where the page shown starts
  readonly offset = input(0, {
    transform: (value: unknown) => Math.max(0, numberAttribute(value, 0)),
  });

  protected readonly users = signal<User[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pagination = signal<Pagination | null>(null);
  // why an admin is not deleted
  protected readonly configured = this.i18n.t('users.configured');

  // the loading of the page shown, replaced by the next one
  private loading?: Subscription;

  constructor() {
    effect(() => {
      const offset = this.offset();
      untracked(() => this.load(offset));
    });
  }

  protected goTo(offset: number): void {
    void this.router.navigate(['/users'], { queryParams: { offset: offset > 0 ? offset : null } });
  }

  protected delete(id: number): void {
    this.service
      .delete(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(this.offset()),
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('users.not_deleted'))),
      });
  }

  private load(offset: number): void {
    this.loading?.unsubscribe();
    this.loading = this.service
      .page(offset)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          // the page is gone, e.g. its last user
          if (page.items.length === 0 && offset > 0) {
            this.goTo(lastPageOffset(page.pagination));
            return;
          }
          this.error.set(null);
          this.users.set(page.items);
          this.pagination.set(page.pagination);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t('users.not_loaded'), {
              403: this.i18n.t('users.forbidden_see'),
            }),
          ),
      });
  }
}
