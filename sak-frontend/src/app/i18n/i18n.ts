import { Injectable, Pipe, PipeTransform, inject } from '@angular/core';
import { CATALOG, TextKey } from './catalog';
import { Language, LANGUAGES, Strings } from './texts';

export type { TextKey } from './catalog';

// the values of the placeholders of a text
export type TextParams = Readonly<Record<string, string | number>>;

// the first language of the browser there are texts for, else english; the browser sends the same
// languages to the api (Accept-Language), so its errors are in the same language
export function languageOf(languages: readonly string[]): Language {
  for (const tag of languages) {
    const language = tag.split('-')[0].toLowerCase();
    if ((LANGUAGES as readonly string[]).includes(language)) return language as Language;
  }
  return 'en';
}

function lookup(texts: Strings, key: string): string | undefined {
  let value: string | Strings | undefined = texts;
  for (const part of key.split('.')) {
    if (typeof value !== 'object') return undefined;
    value = value[part];
  }
  return typeof value === 'string' ? value : undefined;
}

// a text in a language, its placeholders %{name} filled in; a text german misses is english
export function translate(language: Language, key: TextKey, params: TextParams = {}): string {
  const text = lookup(CATALOG[language], key) ?? lookup(CATALOG.en, key) ?? key;
  return text.replace(/%\{([a-z_]+)\}/g, (placeholder, name: string) =>
    String(params[name] ?? placeholder),
  );
}

// the texts of the apps in the language of the browser, the same one for the whole visit
@Injectable({ providedIn: 'root' })
export class I18n {
  readonly language: Language = languageOf(
    typeof navigator === 'undefined' ? [] : (navigator.languages ?? [navigator.language]),
  );

  t(key: TextKey, params?: TextParams): string {
    return translate(this.language, key, params);
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
