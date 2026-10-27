import { Component, computed, inject } from '@angular/core';
import { BucketStore } from './bucket-store';
import { servedDomains } from './buckets.service';

// the configuration tab of a bucket: the addresses its files are served at, its domains are
// changed on its edit page
@Component({
  selector: 'app-bucket-configuration',
  template: `
    @if (store.bucket(); as bucket) {
      <section class="card card-body gap-2" aria-label="Domains">
        @if (served().length) {
          <p class="mb-0">Files uploaded to this bucket are served at the following addresses:</p>
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
            An <span class="font-monospace text-break">index.html</span> is served for a folder,
            e.g. at the address itself.
          </p>
        } @else {
          <p class="mb-0" data-testid="domains">
            Files uploaded to this bucket are not served yet, add a domain on its edit page.
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
