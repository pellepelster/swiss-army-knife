// the apps of the frontend, the index page lists them
export interface AppEntry {
  readonly path: string;
  readonly name: string;
  readonly description: string;
  // the app icon, the one the app is installed with
  readonly icon: string;
}

export const APPS: readonly AppEntry[] = [
  {
    path: '/notes',
    name: 'Notes',
    description: 'Post-Its from text, images or audio',
    icon: 'icons/notes.svg',
  },
  {
    path: '/static-hosting',
    name: 'Static hosting',
    description: 'Websites from S3 buckets',
    icon: 'icons/hosting.svg',
  },
  {
    path: '/redirects',
    name: 'Redirects',
    description: 'Domains that lead elsewhere',
    icon: 'icons/redirects.svg',
  },
];

// the administration of the frontend, the index page lists it below the apps
export const ADMINISTRATION: readonly AppEntry[] = [
  {
    path: '/console',
    name: 'Console',
    description: 'Requests and logs of every site',
    icon: 'icons/console.svg',
  },
];
