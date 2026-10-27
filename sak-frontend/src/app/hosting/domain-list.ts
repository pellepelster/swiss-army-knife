import { Component, model } from '@angular/core';
import { DOMAIN_PATTERN } from '../shared/domains';

// the domains a bucket is served at, one input each; blank inputs are left out by the caller
@Component({
  selector: 'app-domain-list',
  styles: `
    .domain {
      display: flex;
      gap: 8px;
    }
    .remove {
      flex: none;
      width: 42px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: transparent;
      color: var(--ink);
      cursor: pointer;
    }
    fieldset {
      display: grid;
      gap: 8px;
      margin: 0;
      padding: 0;
      border: 0;
    }
    .add-domain {
      justify-self: start;
    }
  `,
  template: `
    <fieldset>
      <legend class="label">Domains</legend>
      @for (domain of domains(); track $index; let index = $index) {
        <div class="domain">
          <input
            class="input mono"
            [id]="'domain-' + index"
            placeholder="www.example.org"
            autocomplete="off"
            autocapitalize="off"
            [attr.aria-label]="'Domain ' + (index + 1)"
            [attr.aria-invalid]="invalid(domain)"
            [value]="domain"
            (input)="set(index, $any($event.target).value)"
          />
          <button
            type="button"
            class="remove"
            [attr.aria-label]="'Remove domain ' + (index + 1)"
            title="Remove domain"
            (click)="remove(index)"
          >
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
        </div>
      } @empty {
        <p class="hint">Not served at a domain yet.</p>
      }
      <button type="button" class="button button-quiet button-small add-domain" (click)="add()">
        <i class="fa-solid fa-plus" aria-hidden="true"></i> Add domain
      </button>
      <p class="hint">
        The objects of the bucket are served at every domain, redirects to the bucket lead to the
        first one. Each domain needs a DNS record pointing at the server.
      </p>
    </fieldset>
  `,
})
export class DomainList {
  readonly domains = model.required<string[]>();

  protected invalid(domain: string): boolean {
    return domain.trim() !== '' && !DOMAIN_PATTERN.test(domain.trim());
  }

  protected set(index: number, value: string): void {
    this.domains.update((domains) => domains.map((domain, i) => (i === index ? value : domain)));
  }

  protected add(): void {
    this.domains.update((domains) => [...domains, '']);
  }

  protected remove(index: number): void {
    this.domains.update((domains) => domains.filter((_, i) => i !== index));
  }
}
