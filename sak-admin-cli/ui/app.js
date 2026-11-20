// the checks first, then the provider tokens when there is none, else the deployments
import { runChecks } from './checks.js';
// no other page imports the page of a deployment, it registers itself (`views.navigate`)
import './deployment.js';
import { showDeployments } from './deployments.js';
import { showProviders } from './providers.js';
import { showVersion } from './update.js';
import { loadTexts } from './views.js';

await loadTexts();
await showVersion();
const next = await runChecks();
if (next === 'providers') {
  await showProviders();
} else {
  await showDeployments();
}
