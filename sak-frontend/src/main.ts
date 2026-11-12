import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { browserLanguage, loadTexts } from './app/i18n/i18n';

// the texts of the language of the browser first, every page shows them
loadTexts(browserLanguage())
  .then(() => bootstrapApplication(App, appConfig))
  .catch((err) => console.error(err));
