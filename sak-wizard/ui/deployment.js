// one deployment: its state and resources, redeploy at the top right leads to the plan of
// bringing it to what the wizard declares, back to the deployments
import { showCreate } from './create.js';
import { STATES, resource } from './deployments.js';
import { show, text } from './views.js';

let current = null;

export function showDeployment(deployment) {
  current = deployment;
  const logo = document.getElementById('deployment-logo');
  logo.src = deployment.provider.logo;
  logo.alt = deployment.provider.name;
  document.getElementById('deployment-name').textContent = deployment.name;
  const badge = document.getElementById('deployment-status');
  badge.dataset.sakState = STATES[deployment.status] ?? 'neutral';
  badge.textContent = text(`ui.deployments.status.${deployment.status}`);
  document.getElementById('deployment-location').textContent = deployment.location
    ? `${text('ui.deployment.location')} ${deployment.location}`
    : '';
  document.getElementById('deployment-resources').replaceChildren(...deployment.resources.map(resource));
  show('view-deployment');
}

document.getElementById('deployment-redeploy').addEventListener('click', () => showCreate({ redeploy: current }));
document.getElementById('deployment-back').addEventListener('click', async () => {
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
});
