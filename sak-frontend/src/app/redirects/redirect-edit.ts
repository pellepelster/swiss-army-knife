import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { errorMessages } from '../shared/api';
import { redirectErrorMessage } from './redirect-store';
import { RedirectTargetFields, redirectTarget, TargetType } from './redirect-target-fields';
import { RedirectsService } from './redirects.service';

// edits where a redirect leads to on a page of its own, the redirected domain can not be
// changed; saving or cancelling goes back to the redirect
@Component({
  selector: 'app-redirect-edit',
  imports: [RedirectTargetFields, RouterLink],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  template: `
    <header class="toolbar">
      <a class="back" [routerLink]="['/redirects', source()]" aria-label="Back to the redirect"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Edit redirect</h1>
    </header>

    @if (error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="form sheet" (submit)="$event.preventDefault(); save()">
        <div class="field">
          <label class="label" for="source-domain">Domain</label>
          <input class="input mono" id="source-domain" [value]="source()" readonly />
          <span class="hint">The redirected domain can not be changed.</span>
        </div>

        <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

        @if (saveError(); as error) {
          <p class="form-errors" role="alert" data-testid="save-error">{{ error }}</p>
        }

        <div class="actions">
          <a class="button button-quiet" [routerLink]="['/redirects', source()]">Cancel</a>
          <button class="button" type="submit" [disabled]="target() === null || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            Save
          </button>
        </div>
      </form>
    }
  `,
})
export class RedirectEdit implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);

  // the route parameter
  readonly source = input.required<string>();

  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly type = signal<TargetType>('domain');
  protected readonly domain = signal('');
  protected readonly bucket = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly target = computed(() =>
    redirectTarget(this.type(), this.domain(), this.bucket()),
  );

  ngOnInit(): void {
    this.service
      .redirect(this.source())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (redirect) => {
          this.type.set(redirect.target.type);
          if (redirect.target.type === 'domain') this.domain.set(redirect.target.domain);
          else this.bucket.set(redirect.target.bucket);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(redirectErrorMessage(error, 'The redirect could not be loaded.')),
      });
  }

  protected save(): void {
    const target = this.target();
    if (target === null) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.service
      .update(this.source(), target)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/redirects', this.source()]),
        error: (error: unknown) => {
          this.saving.set(false);
          this.saveError.set(saveErrorMessage(error));
        },
      });
  }
}

// what the api answered, or what its status tells
function saveErrorMessage(error: unknown): string {
  const messages = errorMessages(error);
  if (messages.length) return messages.join(' ');
  if (error instanceof HttpErrorResponse && error.status === 422)
    return 'The bucket is unknown or not served at a domain.';
  return redirectErrorMessage(error, 'The redirect could not be saved.');
}
