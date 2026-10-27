import { Component, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

// a tab of a detail view, the path of its child route below the view
export interface TabEntry {
  readonly path: string;
  readonly label: string;
}

// the tabs below the overview panel of a detail view, each one a child route
@Component({
  selector: 'app-tab-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="nav nav-underline tab-nav mb-3">
      @for (tab of tabs(); track tab.path) {
        <a class="nav-link" [routerLink]="tab.path" routerLinkActive="active" ariaCurrentWhenActive="page">{{
          tab.label
        }}</a>
      }
    </nav>
  `,
})
export class TabNav {
  readonly tabs = input.required<readonly TabEntry[]>();
}
