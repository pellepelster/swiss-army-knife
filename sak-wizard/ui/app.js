// the splash screen with the checks first, the provider tokens next unless the environment had
// them, the operations last
import { runChecks } from './checks.js';
import { showOperations } from './operations.js';
import { showProviders } from './providers.js';
import { loadTexts } from './views.js';

await loadTexts();
const next = await runChecks();
if (next === 'providers') {
  await showProviders();
} else {
  showOperations();
}
