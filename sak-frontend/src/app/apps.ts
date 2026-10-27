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
];
