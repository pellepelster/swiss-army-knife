import { CATALOG } from './catalog';
import { languageOf, translate } from './i18n';
import { Strings } from './texts';

function paths(texts: Strings, prefix = ''): [string, string][] {
  return Object.entries(texts).flatMap(([key, value]) =>
    typeof value === 'string'
      ? [[prefix + key, value] as [string, string]]
      : paths(value, `${prefix}${key}.`),
  );
}

const placeholders = (text: string) =>
  [...text.matchAll(/%\{([a-z_]+)\}/g)].map((match) => match[1]).sort();

describe('texts', () => {
  it('german has every text of english with the same placeholders, and no other', () => {
    const english = new Map(paths(CATALOG.en));
    const german = new Map(paths(CATALOG.de));
    expect([...german.keys()].sort()).toEqual([...english.keys()].sort());
    for (const [key, text] of english) {
      expect({ key, placeholders: placeholders(german.get(key) ?? '') }).toEqual({
        key,
        placeholders: placeholders(text),
      });
    }
  });

  it('takes the first language of the browser there are texts for, else english', () => {
    expect(languageOf(['de-DE', 'en-US'])).toBe('de');
    expect(languageOf(['fr-FR', 'de'])).toBe('de');
    expect(languageOf(['fr-FR'])).toBe('en');
    expect(languageOf([])).toBe('en');
  });

  it('fills in the placeholders', () => {
    expect(translate('de', 'login.sign_in')).toBe('Anmelden');
    expect(translate('en', 'login.sign_in', { unused: 1 })).toBe('Sign in');
  });
});
