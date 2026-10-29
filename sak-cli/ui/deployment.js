// the page of one deployment: its state, location and resources. Deploy and destroy at the top
// right open the form for that intent; back opens the deployments
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

document.getElementById('deployment-deploy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'deploy' }),
);
document.getElementById('deployment-destroy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'destroy' }),
);
document.getElementById('deployment-back').addEventListener('click', async () => {
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
});
