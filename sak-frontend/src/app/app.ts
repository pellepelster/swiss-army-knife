import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { InstallService } from './install.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `<router-outlet />`,
})
export class App {
  // listens from the start, the browser tells once whether the app can be installed
  private readonly install = inject(InstallService);
}
