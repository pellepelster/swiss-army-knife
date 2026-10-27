import { Component, inject } from '@angular/core';
import { BucketStore } from './bucket-store';

// the configuration tab of a bucket: the domains it is served at; they are changed on the
// bucket's edit page
@Component({
  selector: 'app-bucket-configuration',
  template: `
    @if (store.bucket(); as bucket) {
      <div class="card">
        <div class="card-body">
          <dl class="row mb-0">
            <dt class="col-sm-3">Domains</dt>
            <dd class="col-sm-9 font-mono mb-0" data-testid="domains">
              {{ bucket.domains.join(', ') || '–' }}
            </dd>
          </dl>
        </div>
      </div>
    }
  `,
})
export class BucketConfiguration {
  protected readonly store = inject(BucketStore);
}
