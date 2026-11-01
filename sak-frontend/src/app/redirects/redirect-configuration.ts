import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../i18n/i18n';
import { RedirectStore } from './redirect-store';

// the configuration tab of a redirect: where it leads to, changed on its edit page
@Component({
  selector: 'app-redirect-configuration',
  imports: [RouterLink, TranslatePipe],
  template: `
    @if (store.redirect(); as redirect) {
      <section
        class="card card-body gap-2"
        [attr.aria-label]="'redirects.configuration.target' | t"
      >
        <p class="mb-0" data-testid="target">
          @if (redirect.target.type === 'bucket') {
            {{ 'redirects.configuration.to_bucket_before' | t }}
            <a
              class="font-monospace text-break"
              [routerLink]="['/static-hosting', redirect.target.bucket]"
              >{{ redirect.target.bucket }}</a
            >{{ 'redirects.configuration.to_bucket_after' | t }}
          } @else {
            {{ 'redirects.configuration.to_domain' | t }}
            <span class="font-monospace text-break">{{ redirect.target.domain }}</span
            >.
          }
        </p>
        @if (redirect.location) {
          <p class="form-text mb-0">
            {{ 'redirects.configuration.request_before' | t }}
            <span class="font-monospace text-break">https://{{ redirect.sourceDomain }}/…</span>
            {{ 'redirects.configuration.request_middle' | t }}
            <span class="font-monospace text-break">https://{{ redirect.location }}/…</span
            >{{ 'redirects.configuration.request_after' | t }}
          </p>
        } @else {
          <p class="form-text mb-0">
            {{ 'redirects.configuration.not_served' | t }}
          </p>
        }
      </section>
    }
  `,
})
export class RedirectConfiguration {
  protected readonly store = inject(RedirectStore);
}
