import { Component, computed, inject } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { BucketStore } from './bucket-store';
import { servedDomains } from './buckets.service';

// the configuration tab of a bucket: the addresses its files are served at, its domains are
// changed on its edit page
@Component({
  selector: 'app-bucket-configuration',
  imports: [TranslatePipe],
  template: `
    @if (store.bucket(); as bucket) {
      <section class="card card-body gap-2" [attr.aria-label]="'hosting.configuration.domains' | t">
        @if (served().length) {
          <p class="mb-0">{{ 'hosting.configuration.served_at' | t }}</p>
          <ul class="list-unstyled vstack gap-1 mb-0" data-testid="domains">
            @for (domain of served(); track domain) {
              <li class="font-monospace text-break">
                <a [href]="'https://' + domain + '/'" target="_blank" rel="noopener"
                  >https://{{ domain }}/</a
                >
              </li>
            }
          </ul>
          <p class="form-text mb-0">
            {{ 'hosting.configuration.index_before' | t }}
            <span class="font-monospace text-break">index.html</span>
            {{ 'hosting.configuration.index_after' | t }}
          </p>
        } @else {
          <p class="mb-0" data-testid="domains">
            {{ 'hosting.configuration.not_served' | t }}
          </p>
        }
      </section>
    }
  `,
})
export class BucketConfiguration {
  protected readonly store = inject(BucketStore);

  protected readonly served = computed(() => {
    const bucket = this.store.bucket();
    return bucket ? servedDomains(bucket) : [];
  });
}
