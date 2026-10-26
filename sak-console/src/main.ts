import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// bootstrap only follows the system's dark mode when told so, the csp forbids doing it in an
// inline script in index.html
const darkMode = window.matchMedia('(prefers-color-scheme: dark)');
const applyTheme = () =>
  document.documentElement.setAttribute('data-bs-theme', darkMode.matches ? 'dark' : 'light');
applyTheme();
darkMode.addEventListener('change', applyTheme);

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
