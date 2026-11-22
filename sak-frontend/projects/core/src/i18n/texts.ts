// the texts come from i18n/texts.yml at the root of the repository; sak-i18n writes them into
// catalog.<language>.ts (mise run i18n:generate) and checks that german has every text of english
// with the same placeholders (%{name})
export interface Strings {
  readonly [key: string]: string | Strings;
}

export const LANGUAGES = ['en', 'de'] as const;
export type Language = (typeof LANGUAGES)[number];

// the dotted paths of the texts of a catalog, e.g. login.title
export type Paths<T> = {
  [K in keyof T & string]: T[K] extends string ? K : `${K}.${Paths<T[K]>}`;
}[keyof T & string];
