// only the main create action of a page has the plus sign of sak-theme, the other add actions show
// what they add as its icon (docs/development/UI-UX.md, "Only the main create action has a plus"); checked in
// every template of the app
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
// what an add action that is no main create action shows instead of the plus
const ADDS_A_THING = /fa-camera|fa-microphone|fa-cart-shopping/;
const PLUS = 'sak-icon-plus';

function shown(element: string): string {
  return (
    element.replace(/<[^>]*>/g, ' ') +
    ' ' +
    (element.match(/(aria-label|title)="[^"]*"/g) ?? []).join(' ')
  ).replace(/\s+/g, ' ');
}

describe('the create buttons', () => {
  const buttons = templates(dirname(fileURLToPath(import.meta.url))).flatMap((file) =>
    (readFileSync(file, 'utf8').match(ELEMENTS) ?? []).map((element) => ({ file, element })),
  );

  it('the main create action of a page starts with the plus sign of sak-theme', () => {
    const creates = buttons.filter(
      ({ element }) => CREATES.test(shown(element)) && !ADDS_A_THING.test(element),
    );
    const missing = creates
      .filter(({ element }) => !element.includes(PLUS))
      .map(({ file, element }) => `${file}: ${shown(element).slice(0, 120)}`);
    expect(missing).toEqual([]);
    expect(creates.length).toBeGreaterThanOrEqual(5);
  });

  it('the other add actions show what they add, without the plus', () => {
    const adds = buttons.filter(({ element }) => ADDS_A_THING.test(element));
    const withPlus = adds
      .filter(({ element }) => element.includes(PLUS))
      .map(({ file, element }) => `${file}: ${shown(element).slice(0, 120)}`);
    expect(withPlus).toEqual([]);
    expect(adds.length).toBeGreaterThanOrEqual(4);
  });

  it('a page has one action with the plus at most, shown once or for each screen size', () => {
    const byFile = new Map<string, Set<string>>();
    for (const { file, element } of buttons.filter(({ element }) => element.includes(PLUS))) {
      const label = element.match(/aria-label="([^"]*)"/)?.[1] ?? shown(element).trim();
      byFile.set(file, (byFile.get(file) ?? new Set()).add(label));
    }
    const crowded = [...byFile]
      .filter(([, labels]) => labels.size > 1)
      .map(([file, labels]) => `${file}: ${[...labels].join(', ')}`);
    expect(crowded).toEqual([]);
  });
});
