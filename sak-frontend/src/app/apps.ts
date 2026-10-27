// the apps of the frontend, the index page lists them
export interface AppEntry {
  readonly path: string;
  readonly name: string;
  readonly description: string;
  // a solid icon of font awesome, e.g. fa-note-sticky
  readonly icon: string;
}

export const APPS: readonly AppEntry[] = [
  {
    path: '/notes',
    name: 'Notes',
    description: 'Post-its with labels to sort them',
    icon: 'fa-note-sticky',
  },
];
