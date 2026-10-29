// the page of one deployment, opened with its password: its state, location and resources.
// Destroy at the top right opens the form of a destroy. The toolbar at the bottom: back to the
// list, or check deployment, which plans a deploy of the deployment as it is
import { showCreate } from './create.js';
import { STATES, resource } from './deployments.js';
import { openedDeployment } from './password.js';
import { showPlan } from './run.js';
import { show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const message = document.getElementById('deployment-message');
const checkButton = document.getElementById('deployment-check');
let current = null;

function setMessage(value, failed = false) {
  message.textContent = value;
  message.classList.toggle('status-failed', failed);
}

export function showDeployment(deployment) {
  current = deployment;
  const logo = document.getElementById('deployment-logo');
  logo.src = deployment.provider.logo;
  logo.alt = deployment.provider.name;
  document.getElementById('deployment-name').textContent = deployment.name;
  const badge = document.getElementById('deployment-status');
  badge.dataset.sakState = STATES[deployment.status] ?? 'neutral';
  badge.textContent = text(`shared.deployments.status.${deployment.status}`);
  document.getElementById('deployment-location').textContent = deployment.location
    ? `${text('ui.deployment.location')} ${deployment.location}`
    : '';
  document.getElementById('deployment-resources').replaceChildren(...deployment.resources.map(resource));
  setMessage('');
  checkButton.disabled = false;
  show('view-deployment');
}

// a deploy of the deployment as it is: its location, dns mode and root domain, the user mode and
// the mail server rust detects, the password it was opened with
async function check() {
  const { password } = openedDeployment();
  checkButton.disabled = true;
  setMessage(text('ui.create.planning'));
  try {
    const detected = await invoke('detected_settings', { name: current.name, password });
    const multi = detected.userMode === 'multi';
    const deployment = {
      name: current.name,
      password,
      passwordRepeat: password,
      location: current.location,
      dnsMode: current.dnsMode ?? 'subdomain',
      rootDomain: current.rootDomain ?? null,
      intent: 'deploy',
      deleteData: false,
      userMode: detected.userMode ?? 'single',
      smtp: multi && detected.smtp
        ? {
            host: detected.smtp.host,
            port: detected.smtp.port,
            username: detected.smtp.username ?? '',
            password: detected.smtp.password ?? '',
            starttls: detected.smtp.starttls,
          }
        : null,
      adminEmail: multi ? (detected.adminEmail ?? null) : null,
    };
    const plan = await invoke('plan_deployment', { deployment });
    const shown = current;
    showPlan(plan, deployment, () => showDeployment(shown));
  } catch (error) {
    setMessage(String(error), true);
    checkButton.disabled = false;
  }
}

checkButton.addEventListener('click', check);
document.getElementById('deployment-destroy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'destroy' }),
);
document.getElementById('deployment-back').addEventListener('click', async () => {
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
});
