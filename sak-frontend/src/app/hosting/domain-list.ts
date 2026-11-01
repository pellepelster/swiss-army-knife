import { Component, model } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { DOMAIN_PATTERN } from '../shared/domains';

// the domains a bucket is served at, one input each; blank inputs are left out by the caller
@Component({
  selector: 'app-domain-list',
  imports: [TranslatePipe],
  template: `
    <fieldset class="vstack gap-2">
      <legend class="form-label fs-6 mb-0">{{ 'hosting.configuration.domains' | t }}</legend>
      @for (domain of domains(); track $index; let index = $index) {
        <div class="input-group">
          <input
            class="form-control font-monospace text-break"
            [id]="'domain-' + index"
            placeholder="www.example.org"
            autocomplete="off"
            autocapitalize="off"
            [attr.aria-label]="'hosting.domains.domain' | t: { number: index + 1 }"
            [attr.aria-invalid]="invalid(domain)"
            [class.is-invalid]="invalid(domain)"
            [value]="domain"
            (input)="set(index, $any($event.target).value)"
          />
          <button
            type="button"
            class="btn btn-outline-secondary"
            [attr.aria-label]="'hosting.domains.delete_domain' | t: { number: index + 1 }"
            [title]="'hosting.domains.delete' | t"
            (click)="delete(index)"
          >
            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
          </button>
        </div>
      } @empty {
        <p class="form-text mb-0">{{ 'hosting.domains.none' | t }}</p>
      }
      <button
        type="button"
        class="btn btn-outline-secondary btn-sm align-self-start"
        (click)="add()"
      >
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'hosting.domains.add' | t }}
      </button>
      <p class="form-text mb-0">
        {{ 'hosting.domains.hint' | t }}
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

  protected delete(index: number): void {
    this.domains.update((domains) => domains.filter((_, i) => i !== index));
  }
}
