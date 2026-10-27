import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RedirectStore } from './redirect-store';

// the configuration tab of a redirect: where it leads to, changed on its edit page
@Component({
  selector: 'app-redirect-configuration',
  imports: [RouterLink],
  template: `
    @if (store.redirect(); as redirect) {
      <section class="card card-body gap-2" aria-label="Target">
        <p class="mb-0" data-testid="target">
          @if (redirect.target.type === 'bucket') {
            Redirects to the bucket
            <a
              class="font-monospace text-break"
              [routerLink]="['/static-hosting', redirect.target.bucket]"
              >{{ redirect.target.bucket }}</a
            >, at the first domain it is served at.
          } @else {
            Redirects to the external domain
            <span class="font-monospace text-break">{{ redirect.target.domain }}</span
            >.
          }
        </p>
        @if (redirect.location) {
          <p class="form-text mb-0">
            Every request for
            <span class="font-monospace text-break">https://{{ redirect.sourceDomain }}/…</span> is
            answered with a permanent redirect to
            <span class="font-monospace text-break">https://{{ redirect.location }}/…</span>, the
            path stays.
          </p>
        } @else {
          <p class="form-text mb-0">
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
