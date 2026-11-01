import { TextKey } from './i18n/i18n';

// the apps of the frontend, the index page lists them
interface AppEntry {
  readonly path: string;
  readonly name: TextKey;
  readonly description: TextKey;
  // the app icon, the one the app is installed with
  readonly icon: string;
  // only for the role admin; a user (the role user) has the apps of the lists
  readonly adminOnly?: boolean;
}

// the page of the app of the name, e.g. /grocery for grocery; null for a name of no app
export function appPath(name: string | null): string | null {
  return name ? (APPS.find((app) => app.path === `/${name}`)?.path ?? null) : null;
}

export const APPS: readonly AppEntry[] = [
  {
    path: '/notes',
    name: 'app.notes',
    description: 'app.notes_description',
    icon: 'icons/notes.svg',
  },
  {
    path: '/grocery',
    name: 'app.grocery',
    description: 'app.grocery_description',
    icon: 'icons/grocery.svg',
  },
  {
    path: '/static-hosting',
    name: 'app.static_hosting',
    description: 'app.static_hosting_description',
    icon: 'icons/hosting.svg',
    adminOnly: true,
  },
  {
    path: '/redirects',
    name: 'app.redirects',
    description: 'app.redirects_description',
    icon: 'icons/redirects.svg',
    adminOnly: true,
  },
];
