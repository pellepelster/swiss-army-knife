import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ColorSchemeService } from './color-scheme.service';
import { FaviconService } from './favicon.service';
import { InstallService } from './install.service';
import { ManifestService } from './manifest.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `<router-outlet />`,
})
export class App {
  // listens from the start, the browser tells once whether the app can be installed
  private readonly install = inject(InstallService);
  // follows the navigation from the start, every page shows its favicon
  private readonly favicon = inject(FaviconService);
  // and the manifest of its app, every app installs as its own
  private readonly manifest = inject(ManifestService);
  // bootstrap follows the light or dark mode of the browser
  private readonly colorScheme = inject(ColorSchemeService);
}
