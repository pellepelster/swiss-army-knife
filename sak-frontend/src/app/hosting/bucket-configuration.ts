import { Component, computed, inject } from '@angular/core';
import { BucketStore } from './bucket-store';
import { servedDomains } from './buckets.service';

// the configuration tab of a bucket: the addresses its files are served at, its domains are
// changed on its edit page
@Component({
  selector: 'app-bucket-configuration',
  styleUrl: '../shared/app-parts.scss',
  styles: `
    .configuration {
      display: grid;
      gap: 10px;
    }
    p {
      margin: 0;
    }
    ul {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
  `,
  template: `
    @if (store.bucket(); as bucket) {
      <section class="sheet configuration" aria-label="Domains">
        @if (served().length) {
          <p>Files uploaded to this bucket are served at the following addresses:</p>
          <ul data-testid="domains">
            @for (domain of served(); track domain) {
              <li class="mono">
                <a [href]="'https://' + domain + '/'" target="_blank" rel="noopener"
                  >https://{{ domain }}/</a
                >
              </li>
            }
          </ul>
          <p class="hint">
            An <span class="mono">index.html</span> is served for a folder, e.g. at the address
            itself.
          </p>
        } @else {
          <p data-testid="domains">
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
