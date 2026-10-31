// the apps of the frontend, the index page lists them
interface AppEntry {
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
    path: '/grocery',
    name: 'Grocery',
    description: 'Shopping lists to share',
    icon: 'icons/grocery.svg',
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
