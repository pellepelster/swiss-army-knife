// the specs run with the english texts, which main.ts loads in the app for an english browser
import { TEXTS } from '@sak/core/catalog.en';
import { useTexts } from '@sak/core';

useTexts('en', TEXTS);
