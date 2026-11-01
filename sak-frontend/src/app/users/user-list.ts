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
import { apiErrorMessage, lastPageOffset, Pagination } from '../shared/api';
import { PaginationControl } from '../shared/pagination';
import { User, UsersService } from './users.service';

// who may sign in, a page at a time, as a table of the address, the name, the role and the
// actions: the admins of the configuration of the deployment, then the users an admin added. A user is added on a page of its own
// and deleted after a confirmation in its place (UX-4.1); an admin of the configuration is not
@Component({
  selector: 'app-user-list',
  imports: [PaginationControl, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Users</h1>
      <a class="btn btn-primary" routerLink="/users/create">
        <i class="sak-icon-plus" aria-hidden="true"></i> Add user
      </a>
    </header>

    <p class="text-body-secondary">
      The admins come from the configuration of the deployment. A user signs in with a link by
      e-mail and uses the notes and the grocery lists.
    </p>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <div class="card table-responsive">
        <table class="table align-middle mb-0" aria-label="Users">
          <thead>
            <tr>
              <th scope="col">E-mail</th>
              <th scope="col">Name</th>
              <th scope="col">Role</th>
              <th scope="col" class="text-end">Actions</th>
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
                    <span class="text-body-secondary" aria-label="No name">–</span>
                  }
                </td>
                <td>
                  <span class="badge border text-body-secondary fw-normal" data-testid="role">{{
                    user.configured ? 'admin' : 'user'
                  }}</span>
                </td>
                <td class="text-end">
                  @if (confirming() === user.id && user.id !== null) {
                    <div class="d-flex flex-wrap align-items-center justify-content-end gap-2">
                      <span class="small"
                        >Delete {{ user.name ?? user.email }}? They can no longer sign in; their
                        data stays.</span
                      >
                      <div class="btn-group" role="group" aria-label="Delete the user">
                        <button type="button" class="btn btn-danger" (click)="delete(user.id)">
                          Delete
                        </button>
                        <button
                          type="button"
                          class="btn btn-outline-secondary"
                          (click)="confirming.set(null)"
                        >
                          Keep
                        </button>
                      </div>
                    </div>
                  } @else {
                    <button
                      type="button"
                      class="btn btn-outline-danger"
                      [disabled]="user.configured"
                      [attr.aria-label]="'Delete ' + user.email"
                      [title]="
                        user.configured ? 'Set by the configuration of the deployment' : 'Delete'
                      "
                      (click)="confirming.set(user.id)"
                    >
                      Delete
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <app-pagination
        label="Pages of the users"
        [pagination]="pagination()"
        (offsetChange)="goTo($event)"
      />
    } @else if (!error()) {
      <p class="text-body-secondary">Loading…</p>
    }
  `,
})
export class UserList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(UsersService);
  private readonly router = inject(Router);

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
          this.error.set(apiErrorMessage(error, 'The user could not be deleted.')),
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
            apiErrorMessage(error, 'The users could not be loaded.', {
              403: 'Only an admin sees the users.',
            }),
          ),
      });
  }
}
