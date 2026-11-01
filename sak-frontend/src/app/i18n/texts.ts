// the texts of one feature in every language: english is the source and gives the shape, german
// must have every text of it (the compiler checks), with the same placeholders (texts.spec.ts).
// A placeholder is %{name}, the same as in the catalogs of the backend
export interface Strings {
  readonly [key: string]: string | Strings;
}

// the shape of english with a text of its own for every key
export type Translation<T> = {
  readonly [K in keyof T]: T[K] extends string ? string : Translation<T[K]>;
};

export const LANGUAGES = ['en', 'de'] as const;
export type Language = (typeof LANGUAGES)[number];

export function texts<const T extends Strings>(
  en: T,
  de: Translation<T>,
): { en: T; de: Translation<T> } {
  return { en, de };
}

// the dotted paths of the texts of a catalog, e.g. login.title
export type Paths<T> = {
  [K in keyof T & string]: T[K] extends string ? K : `${K}.${Paths<T[K]>}`;
}[keyof T & string];
