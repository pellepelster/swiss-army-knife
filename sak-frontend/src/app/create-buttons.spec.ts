// every button or link that creates or adds something starts with the plus sign of sak-theme,
// in every template of the app (AGENT.md, "UI (both apps)")
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

function templates(dir: string): string[] {
  return readdirSync(dir).flatMap((name: string) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return templates(path);
    }
    return name.endsWith('.ts') && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

// a button or link with what it shows: its text, aria-label and title
const ELEMENTS = /<(button|a)\b[^>]*>[\s\S]*?<\/\1>/g;
const CREATES = /\b(Create|Add|New)\b/;

describe('the create buttons', () => {
  it('start with the plus sign of sak-theme', () => {
    const missing: string[] = [];
    let found = 0;
    for (const file of templates(dirname(fileURLToPath(import.meta.url)))) {
      const source = readFileSync(file, 'utf8');
      for (const element of source.match(ELEMENTS) ?? []) {
        const shown = element.replace(/<[^>]*>/g, ' ') + ' ' + (element.match(/(aria-label|title)="[^"]*"/g) ?? []).join(' ');
        if (!CREATES.test(shown)) {
          continue;
        }
        found += 1;
        if (!element.includes('sak-icon-plus')) {
          missing.push(`${file}: ${element.replace(/\s+/g, ' ').slice(0, 120)}`);
        }
      }
    }
    expect(missing).toEqual([]);
    expect(found).toBeGreaterThanOrEqual(7);
  });
});
