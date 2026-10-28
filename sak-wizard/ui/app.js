// the splash screen with the checks first, the provider tokens next unless the environment had
// them, the deployments last
import { runChecks } from './checks.js';
import { showDeployments } from './deployments.js';
import { showProviders } from './providers.js';
import { loadTexts } from './views.js';

await loadTexts();
const next = await runChecks();
if (next === 'providers') {
  await showProviders();
} else {
  await showDeployments();
}
