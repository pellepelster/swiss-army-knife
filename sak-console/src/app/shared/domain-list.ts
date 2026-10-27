import { Component, model } from '@angular/core';
import { DOMAIN_PATTERN } from './domains';
import { Icon } from './icon';

// a list of domains to edit, one input each; blank inputs are left out by the caller
@Component({
  selector: 'app-domain-list',
  imports: [Icon],
  template: `
    <fieldset>
      <legend class="form-label fs-6 mb-1">Domains</legend>
      @for (domain of domains(); track $index; let index = $index) {
        <div class="input-group mb-2">
          <input
            class="form-control font-mono"
            [id]="'domain-' + index"
            placeholder="www.example.org"
            autocomplete="off"
            [attr.aria-label]="'Domain ' + (index + 1)"
            [class.is-invalid]="invalid(domain)"
            [value]="domain"
            (input)="set(index, $any($event.target).value)"
          />
          <button
            class="btn btn-outline-secondary"
            type="button"
            [attr.aria-label]="'Remove domain ' + (index + 1)"
            title="Remove domain"
            (click)="remove(index)"
          >
            <app-icon name="x" />
          </button>
        </div>
      } @empty {
        <div class="form-text mb-2">Not served at a domain.</div>
      }
      <button class="btn btn-sm btn-outline-secondary" type="button" (click)="add()">Add domain</button>
      <div class="form-text">Caddy serves the bucket's objects at every domain, redirects to the bucket lead to the first.</div>
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
