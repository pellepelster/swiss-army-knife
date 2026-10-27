import { Component, inject } from '@angular/core';
import { BucketStore } from './bucket-store';

// the configuration tab of a bucket: the domains it is served at, changed on its edit page
@Component({
  selector: 'app-bucket-configuration',
  styleUrl: './hosting.scss',
  styles: `
    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }
  `,
  template: `
    @if (store.bucket(); as bucket) {
      <dl class="facts sheet">
        <dt>Domains</dt>
        <dd data-testid="domains">
          @if (bucket.domains.length) {
            <ul>
              @for (domain of bucket.domains; track domain) {
                <li class="mono">
                  <a [href]="'https://' + domain" target="_blank" rel="noopener">{{ domain }}</a>
                </li>
              }
            </ul>
          } @else {
            <span class="muted">Not served at a domain</span>
          }
        </dd>
      </dl>
    }
  `,
})
export class BucketConfiguration {
  protected readonly store = inject(BucketStore);
}
