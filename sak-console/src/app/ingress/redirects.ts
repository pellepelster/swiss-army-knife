import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Redirect, RedirectsService } from './redirects.service';

// the domains caddy redirects, to an external domain or to the domain a bucket is served at; they
// are added on their own page
@Component({
  selector: 'app-redirects',
  imports: [RouterLink],
  template: `
    <div class="page">
      <div class="page-header d-flex flex-wrap align-items-end justify-content-between gap-2">
        <div>
          <h1 class="mb-1">Redirects</h1>
          <div class="page-subtitle">Every request for a redirected domain is answered with a permanent redirect.</div>
        </div>
        <a class="btn btn-primary" routerLink="/redirects/create">Add redirect</a>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      <div class="card">
        <table class="table mb-0" aria-label="Redirects">
          <thead>
            <tr>
              <th scope="col">Domain</th>
              <th scope="col">Target</th>
              <th scope="col">Redirects to</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (redirect of redirects(); track redirect.sourceDomain) {
              <tr [attr.data-testid]="'redirect-' + redirect.sourceDomain">
                <td class="font-mono">{{ redirect.sourceDomain }}</td>
                <td>
                  @if (redirect.target.type === 'bucket') {
                    <span class="badge text-bg-secondary me-1">bucket</span>
                    <a class="font-mono" [routerLink]="['/s3/buckets', redirect.target.bucket]">{{ redirect.target.bucket }}</a>
                  } @else {
                    <span class="badge text-bg-secondary me-1">domain</span>
                    <span class="font-mono">{{ redirect.target.domain }}</span>
                  }
                </td>
                <td class="font-mono" data-testid="location">
                  @if (redirect.location) {
                    https://{{ redirect.location }}
                  } @else {
                    <span class="text-secondary">nowhere, the bucket is not served at a domain</span>
                  }
                </td>
                <td class="text-end">
                  @if (confirming() === redirect.sourceDomain) {
                    <button class="btn btn-sm btn-danger me-1" type="button" (click)="delete(redirect)">Delete</button>
                    <button class="btn btn-sm btn-outline-secondary" type="button" (click)="confirming.set(null)">
                      Cancel
                    </button>
                  } @else {
                    <button
                      class="btn btn-sm btn-outline-danger"
                      type="button"
                      [attr.aria-label]="'Delete the redirect of ' + redirect.sourceDomain"
                      (click)="confirming.set(redirect.sourceDomain)"
                    >
                      Delete
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="text-center text-secondary py-4">No redirects.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class Redirects {
  private readonly service = inject(RedirectsService);

  protected readonly redirects = signal<Redirect[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.load();
  }

  protected delete(redirect: Redirect): void {
    this.confirming.set(null);
    this.subscriptions.add(
      this.service.delete(redirect.sourceDomain).subscribe({
        next: () => this.load(),
        error: () => this.error.set(`The redirect of ${redirect.sourceDomain} could not be deleted.`),
      }),
    );
  }

  private load(): void {
    this.subscriptions.add(
      this.service.redirects().subscribe({
        next: (redirects) => {
          this.error.set(null);
          this.redirects.set(redirects);
        },
        error: (error: unknown) =>
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the redirects.'
              : 'The redirects could not be loaded.',
          ),
      }),
    );
  }
}
