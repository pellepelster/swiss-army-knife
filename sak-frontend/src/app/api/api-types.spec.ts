// the types of the api are the ones of openapi.json at the root of the repository: the script that
// writes them (npm run api:types) checks the committed schema.ts, run in node, not bundled
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

describe('the types of the api', () => {
  it('are current with openapi.json, else mise run api:generate writes them', () => {
    const frontend = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
    expect(() =>
      execFileSync(process.execPath, [join(frontend, 'scripts', 'api-types.mjs'), '--check'], {
        stdio: 'pipe',
      }),
    ).not.toThrow();
  });
});
