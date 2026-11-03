// an installed web app has the name of its android app (docs/development/GLOSSARY.md): name and
// short_name of public/<app>.webmanifest are app_name of the app in i18n/texts.yml
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const texts = readFileSync(join(frontend, '..', 'i18n', 'texts.yml'), 'utf8');

// app_name of the top-level section of the app, e.g. grocery:
function appName(app: string): string | undefined {
  const section = texts.split(/^(?=\S)/m).find((part) => part.startsWith(`${app}:`));
  return section?.match(/^\s+app_name:\s*"([^"]+)"/m)?.[1];
}

describe('the manifests of the web apps', () => {
  for (const app of ['grocery', 'notes']) {
    it(`name ${app} as its android app`, () => {
      const manifest = JSON.parse(readFileSync(join(frontend, 'public', `${app}.webmanifest`), 'utf8'));
      const name = appName(app);
      expect(name).toMatch(/^SAK /);
      expect(manifest.name).toBe(name);
      expect(manifest.short_name).toBe(name);
    });
  }
});
