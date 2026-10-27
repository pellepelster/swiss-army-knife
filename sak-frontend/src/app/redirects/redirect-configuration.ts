import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RedirectStore } from './redirect-store';

// the configuration tab of a redirect: where it leads to, changed on its edit page
@Component({
  selector: 'app-redirect-configuration',
  imports: [RouterLink],
  styles: `
    .configuration {
      display: grid;
      gap: 10px;
    }
    p {
      margin: 0;
    }
  `,
  template: `
    @if (store.redirect(); as redirect) {
      <section class="sheet configuration" aria-label="Target">
        <p data-testid="target">
          @if (redirect.target.type === 'bucket') {
            Redirects to the bucket
            <a class="mono" [routerLink]="['/static-hosting', redirect.target.bucket]">{{
              redirect.target.bucket
            }}</a
            >, at the first domain it is served at.
          } @else {
            Redirects to the external domain
            <span class="mono">{{ redirect.target.domain }}</span
            >.
          }
        </p>
        @if (redirect.location) {
          <p class="hint">
            Every request for <span class="mono">https://{{ redirect.sourceDomain }}/…</span> is
            answered with a permanent redirect to
            <span class="mono">https://{{ redirect.location }}/…</span>, the path stays.
          </p>
        } @else {
          <p class="hint">
            The bucket is not served at a domain, the redirect leads nowhere until it is.
          </p>
        }
      </section>
    }
  `,
})
export class RedirectConfiguration {
  protected readonly store = inject(RedirectStore);
}
