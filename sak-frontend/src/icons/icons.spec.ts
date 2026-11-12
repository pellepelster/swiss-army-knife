// the icons the app uses are those of src/icons/icons.css (scripts/icons.mjs, mise run frontend:icons)
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

describe('the icons', () => {
  it('are current for the icons the app uses', () => {
    expect(() =>
      execFileSync(process.execPath, [join(frontend, 'scripts', 'icons.mjs'), '--check'], {
        stdio: 'pipe',
      }),
    ).not.toThrow();
  });
});
