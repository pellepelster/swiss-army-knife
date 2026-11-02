// the types of the api (src/app/api/schema.ts) from openapi.json at the root of the repository, the
// document the routes of sak-backend describe (mise run api:generate); formatted with prettier.
// --check writes nothing and fails while the committed file is not current (api-types.spec.ts)
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';
import { format, resolveConfig } from 'prettier';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const document = join(frontend, '..', 'openapi.json');
const target = join(frontend, 'src', 'app', 'api', 'schema.ts');

const header =
  '// generated from openapi.json by scripts/api-types.mjs (npm run api:types), not edited\n';
const ast = await openapiTS(JSON.parse(readFileSync(document, 'utf8')));
const options = (await resolveConfig(target)) ?? {};
const types = await format(header + astToString(ast), { ...options, filepath: target });

if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== types) {
    console.error('src/app/api/schema.ts is not current, run mise run api:generate');
    process.exit(1);
  }
} else {
  writeFileSync(target, types);
}
