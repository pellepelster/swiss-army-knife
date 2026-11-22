// who imports whom (AGENTS.md): an app imports core (and testing), redirects also hosting; core
// imports no app; only the shell imports the apps. Across libraries by name, never by a path
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const shell = dirname(fileURLToPath(import.meta.url));
const frontend = join(shell, '..', '..');
const projects = join(frontend, 'projects');

// what a library imports besides itself; a library not named here imports core and testing
const SUPPORT = ['core', 'testing'];
const ALLOWED: Record<string, string[]> = {
  core: ['testing'],
  testing: ['core'],
  redirects: [...SUPPORT, 'hosting'],
};

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name: string) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return sources(path);
    }
    return name.endsWith('.ts') ? [path] : [];
  });
}

// the modules a file imports, statically or with import()
function imports(file: string): string[] {
  return [
    ...readFileSync(file, 'utf8').matchAll(/(?:from\s+|import\(\s*|import\s+)['"]([^'"]+)['"]/g),
  ].map((match) => match[1]);
}

const inside = (dir: string, path: string) => !relative(dir, path).startsWith('..');

describe('the libraries of the frontend', () => {
  const libraries = readdirSync(projects);

  it('import only what they may, by name', () => {
    const wrong = libraries.flatMap((library) => {
      const root = join(projects, library, 'src');
      const allowed = ALLOWED[library] ?? SUPPORT;
      return sources(root).flatMap((file) =>
        imports(file)
          .filter((module) =>
            module.startsWith('.')
              ? !inside(root, resolve(dirname(file), module))
              : module.startsWith('@sak/') &&
                (!allowed.includes(module.split('/')[1]) ||
                  // core takes testing in its specs only
                  (library === 'core' && !file.endsWith('.spec.ts'))),
          )
          .map((module) => `${relative(frontend, file).split(sep).join('/')}: ${module}`),
      );
    });
    expect(wrong).toEqual([]);
  });

  it('are imported by the shell by name', () => {
    const wrong = sources(join(frontend, 'src')).flatMap((file) =>
      imports(file)
        .filter(
          (module) => module.startsWith('.') && inside(projects, resolve(dirname(file), module)),
        )
        .map((module) => `${relative(frontend, file).split(sep).join('/')}: ${module}`),
    );
    expect(wrong).toEqual([]);
  });
});
