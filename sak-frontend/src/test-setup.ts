// the specs run with the english texts, which main.ts loads in the app for an english browser
import { TEXTS } from './app/i18n/catalog.en';
import { useTexts } from './app/i18n/i18n';

useTexts('en', TEXTS);
