// the splash screen with the checks first. Then the provider tokens when there is none, else the
// deployments
import { runChecks } from './checks.js';
// every page registers how it opens (`views.navigate`); the page of a deployment is imported by no
// other page
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
