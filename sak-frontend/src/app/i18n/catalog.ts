import { APP } from '../texts';
import { AUTH } from '../auth/texts';
import { GROCERY } from '../grocery/texts';
import { HEALTH } from '../health/texts';
import { HOME } from '../home/texts';
import { HOSTING } from '../hosting/texts';
import { LOGIN } from '../login/texts';
import { NOTES } from '../notes/texts';
import { REDIRECTS } from '../redirects/texts';
import { SETTINGS } from '../settings/texts';
import { SHARED } from '../shared/texts';
import { SIGNIN } from '../signin/texts';
import { USERS } from '../users/texts';
import { Paths } from './texts';

// the texts of every feature, by language; a feature adds its texts here under its name
export const CATALOG = {
  en: {
    app: APP.en,
    home: HOME.en,
    signin: SIGNIN.en,
    auth: AUTH.en,
    shared: SHARED.en,
    grocery: GROCERY.en,
    notes: NOTES.en,
    hosting: HOSTING.en,
    redirects: REDIRECTS.en,
    settings: SETTINGS.en,
    users: USERS.en,
    health: HEALTH.en,
    login: LOGIN.en,
  },
  de: {
    app: APP.de,
    home: HOME.de,
    signin: SIGNIN.de,
    auth: AUTH.de,
    shared: SHARED.de,
    grocery: GROCERY.de,
    notes: NOTES.de,
    hosting: HOSTING.de,
    redirects: REDIRECTS.de,
    settings: SETTINGS.de,
    users: USERS.de,
    health: HEALTH.de,
    login: LOGIN.de,
  },
} as const;

// every key of a text, e.g. login.title; german has the same keys (texts)
export type TextKey = Paths<typeof CATALOG.en>;
