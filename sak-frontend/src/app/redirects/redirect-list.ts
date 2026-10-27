import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Redirect, RedirectsService } from './redirects.service';

// the domains caddy redirects, to an external domain or to the domain a bucket is served at;
// each leads to its page, a redirect is created on a page of its own
@Component({
  selector: 'app-redirect-list',
  imports: [RouterLink],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  styles: `
    .redirects {
      display: grid;
      gap: 10px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .redirect {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 16px;
    }
    .route {
      display: grid;
      flex: 1;
      gap: 4px;
      min-width: 0;
    }
    .source {
      font-weight: 600;
    }
    .target {
      color: var(--muted);
      font-size: 0.9rem;
    }
    .kind {
      margin-right: 6px;
      padding: 1px 8px;
      border: 1px solid var(--line);
      border-radius: 999px;
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .intro {
      margin: 0 0 16px;
    }
    .empty {
      padding: 32px 16px;
      text-align: center;
    }
  `,
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/" aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Redirects</h1>
      <a class="button" routerLink="/redirects/create">
        <i class="fa-solid fa-plus" aria-hidden="true"></i> Create redirect
      </a>
    </header>

    <p class="intro muted">
      Every request for a redirected domain is answered with a permanent redirect, the path stays.
    </p>

    @if (error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <ul class="redirects" aria-label="Redirects">
        @for (redirect of redirects(); track redirect.sourceDomain) {
          <li class="redirect sheet" [attr.data-testid]="'redirect-' + redirect.sourceDomain">
            <div class="route">
              <a class="source mono" [routerLink]="['/redirects', redirect.sourceDomain]">{{
                redirect.sourceDomain
              }}</a>
              <span class="target">
                <i class="fa-solid fa-arrow-right" aria-hidden="true"></i>
                <span class="kind">{{ redirect.target.type }}</span>
                @if (redirect.target.type === 'bucket') {
                  <a class="mono" [routerLink]="['/static-hosting', redirect.target.bucket]">{{
                    redirect.target.bucket
                  }}</a>
                } @else {
                  <span class="mono">{{ redirect.target.domain }}</span>
                }
              </span>
              <span class="target mono" data-testid="location">
                @if (redirect.location) {
                  https://{{ redirect.location }}
                } @else {
                  nowhere, the bucket is not served at a domain
                }
              </span>
            </div>
          </li>
        } @empty {
          <li class="empty sheet">No redirects yet.</li>
        }
      </ul>
    }
  `,
})
export class RedirectList {
  private readonly service = inject(RedirectsService);

  protected readonly redirects = signal<Redirect[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.load();
  }

  private load(): void {
    this.subscriptions.add(
      this.service.redirects().subscribe({
        next: (redirects) => {
          this.error.set(null);
          this.redirects.set(redirects);
          this.loaded.set(true);
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
