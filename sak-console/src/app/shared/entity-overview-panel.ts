import { Component, input } from '@angular/core';
import { LocalDateTimePipe } from './local-date-time.pipe';

// one labelled value of the overview panel
@Component({
  selector: 'app-overview-field',
  template: `
    <div class="label-mono">{{ label() }}</div>
    <div class="font-mono fw-semibold"><ng-content /></div>
  `,
  styles: ':host { display: block; }',
})
export class OverviewField {
  readonly label = input.required<string>();
}

// the panel on top of every detail view: the name of the entity, the fields its view projects
// and when it was created and updated
@Component({
  selector: 'app-entity-overview-panel',
  imports: [LocalDateTimePipe, OverviewField],
  template: `
    <div class="card mb-3" aria-label="Overview" role="region">
      <div class="card-body">
        <div class="d-flex align-items-center column-gap-5 row-gap-2 flex-wrap">
          <app-overview-field label="Name">{{ name() }}</app-overview-field>
          <ng-content />
        </div>
        @if (created() || updated()) {
          <div class="d-flex align-items-center column-gap-5 row-gap-2 flex-wrap mt-3">
            @if (created(); as created) {
              <app-overview-field label="Created" data-testid="created">{{
                created | localDateTime: 'medium' : 'medium'
              }}</app-overview-field>
            }
            @if (updated(); as updated) {
              <app-overview-field label="Updated" data-testid="updated">{{
                updated | localDateTime: 'medium' : 'medium'
              }}</app-overview-field>
            }
          </div>
        }
      </div>
    </div>
  `,
})
export class EntityOverviewPanel {
  readonly name = input.required<string>();
  readonly created = input<string | null | undefined>(null);
  readonly updated = input<string | null | undefined>(null);
}
