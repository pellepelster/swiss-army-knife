import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SitesService } from '../sites/sites.service';

@Component({
  selector: 'app-home',
  imports: [RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <h1 class="mb-1">Overview</h1>
        <div class="page-subtitle">The sites the ingress serves, their requests are in the logs.</div>
      </div>
      <div class="label-mono mb-2">Sites</div>
      <div class="card-grid">
        @for (site of sites(); track site.id) {
          <a class="card-link" [routerLink]="['/sites', site.id]" [attr.data-testid]="'site-' + site.id">
            <div class="card h-100">
              <div class="card-body">
                <div class="fw-semibold font-mono">{{ site.id }}</div>
                <div class="page-subtitle font-mono">
                  {{ site.domains.length ? site.domains.join(', ') : 'no domains' }}
                </div>
              </div>
            </div>
          </a>
        }
      </div>
    </div>
  `,
})
export class Home {
  private readonly sitesService = inject(SitesService);

  protected readonly sites = computed(() => this.sitesService.sites()?.sites ?? []);

  constructor() {
    this.sitesService.load();
  }
}
