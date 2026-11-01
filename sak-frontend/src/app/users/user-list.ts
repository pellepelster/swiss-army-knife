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
import { User, UsersService } from './users.service';

// who may sign in, a page at a time, as a table of the address, the name, the role and the
// actions: the admins of the configuration of the deployment, then the users an admin added. A user is added on a page of its own
// and deleted after a confirmation in its place (UX-4.1); an admin of the configuration is not
@Component({
  selector: 'app-user-list',
  imports: [PaginationControl, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        [attr.aria-label]="'users.all_apps' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'users.title' | t }}</h1>
      <a class="btn btn-primary" routerLink="/users/create">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'users.add_user' | t }}
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
              <th scope="col">{{ 'users.column.name' | t }}</th>
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
                  @if (confirming() === user.id && user.id !== null) {
                    <div class="d-flex flex-wrap align-items-center justify-content-end gap-2">
                      <span class="small">{{
                        'users.confirm_delete' | t: { name: user.name ?? user.email }
                      }}</span>
                      <div
                        class="btn-group"
                        role="group"
                        [attr.aria-label]="'users.delete_the_user' | t"
                      >
                        <button type="button" class="btn btn-danger" (click)="delete(user.id)">
                          {{ 'users.delete' | t }}
                        </button>
                        <button
                          type="button"
                          class="btn btn-outline-secondary"
                          (click)="confirming.set(null)"
                        >
                          {{ 'users.keep' | t }}
                        </button>
                      </div>
                    </div>
                  } @else {
                    <button
                      type="button"
                      class="btn btn-outline-danger"
                      [disabled]="user.configured"
                      [attr.aria-label]="'users.delete_user' | t: { email: user.email }"
                      [title]="(user.configured ? 'users.configured' : 'users.delete') | t"
                      (click)="confirming.set(user.id)"
                    >
                      {{ 'users.delete' | t }}
                    </button>
                  }
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
      <p class="text-body-secondary">{{ 'users.loading' | t }}</p>
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
  // the id of the user whose deletion asks for the confirmation
  protected readonly confirming = signal<number | null>(null);

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
    this.confirming.set(null);
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
