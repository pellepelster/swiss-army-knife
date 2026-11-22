import { Injectable, Pipe, PipeTransform, inject } from '@angular/core';
import type { TextKey } from './catalog.en';
import { Language, LANGUAGES, Strings } from './texts';

export type { TextKey } from './catalog.en';

// the values of the placeholders of a text
export type TextParams = Readonly<Record<string, string | number>>;

// the first language of the browser with texts, else english; the api answers in the same one
// (Accept-Language)
export function languageOf(languages: readonly string[]): Language {
  for (const tag of languages) {
    const language = tag.split('-')[0].toLowerCase();
    if ((LANGUAGES as readonly string[]).includes(language)) return language as Language;
  }
  return 'en';
}

// the language of the browser, the same one for the whole visit
export function browserLanguage(): Language {
  return languageOf(
    typeof navigator === 'undefined' ? [] : (navigator.languages ?? [navigator.language]),
  );
}

// only the catalog of the language of the visit is loaded, before the app starts (main.ts), so
// the other languages are not in the bundle
let current: { language: Language; texts: Strings } = { language: 'en', texts: {} };

export function useTexts(language: Language, texts: Strings): void {
  current = { language, texts };
}

export async function loadTexts(language: Language): Promise<void> {
  const catalog = language === 'de' ? await import('./catalog.de') : await import('./catalog.en');
  useTexts(language, catalog.TEXTS);
}

function lookup(texts: Strings, key: string): string | undefined {
  let value: string | Strings | undefined = texts;
  for (const part of key.split('.')) {
    if (typeof value !== 'object') return undefined;
    value = value[part];
  }
  return typeof value === 'string' ? value : undefined;
}

// a text of the texts, its placeholders %{name} filled in; a missing text is its key
export function translate(texts: Strings, key: TextKey, params: TextParams = {}): string {
  const text = lookup(texts, key) ?? key;
  return text.replace(/%\{([a-z_]+)\}/g, (placeholder, name: string) =>
    String(params[name] ?? placeholder),
  );
}

// the texts of the apps in the language of the visit (loadTexts)
@Injectable({ providedIn: 'root' })
export class I18n {
  readonly language: Language = current.language;

  t(key: TextKey, params?: TextParams): string {
    return translate(current.texts, key, params);
  }
}

// the title of a route in the language of the browser: { path: 'notes', title: pageTitle('app.notes') }
export function pageTitle(key: TextKey): () => string {
  return () => inject(I18n).t(key);
}

// {{ 'login.sign_in' | t }}, {{ 'notes.count' | t: { count: 3 } }}
@Pipe({ name: 't' })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: TextKey, params?: TextParams): string {
    return this.i18n.t(key, params);
  }
}
