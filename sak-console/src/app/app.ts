import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  template: `
    <header>sak console</header>
    <main>
      <router-outlet />
    </main>
  `,
})
export class App {}
