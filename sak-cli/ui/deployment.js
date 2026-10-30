// the page of one deployment, opened with its password: its state, location and resources. It
// checks the deployment when it opens: a deploy of the deployment as it is, planned in rust, its
// rows those of the plan, the provisioning included. The toolbar at the top right: destroy, which
// opens the form of a destroy, and check, which checks again. At the bottom right, one group:
// back to the list, force deploy (after a check, the playbook runs again) and deploy changes (only
// when the check found changes). The one green button is check, or deploy changes when there are changes
import { showCreate } from './create.js';
import { STATES, resource } from './deployments.js';
import { openedDeployment } from './password.js';
import { row, runChecked } from './run.js';
import { show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const message = document.getElementById('deployment-message');
const list = document.getElementById('deployment-resources');
const badge = document.getElementById('deployment-status');
const checkButton = document.getElementById('deployment-check');
const deployButton = document.getElementById('deployment-deploy');
const forceButton = document.getElementById('deployment-force');
let current = null;
// the plan of the last check, its rows are those of the run
let checked = null;

function setMessage(value, failed = false) {
  message.textContent = value;
  message.classList.toggle('status-failed', failed);
}

// the state the check found; none before it
function setState(state) {
  badge.hidden = !state;
  badge.dataset.sakState = STATES[state] ?? 'neutral';
  badge.textContent = state ? text(`shared.deployments.status.${state}`) : '';
}

// the green button of the page
function setPrimary(primary) {
  for (const button of [checkButton, deployButton]) {
    button.classList.toggle('btn-primary', button === primary);
    button.classList.toggle('btn-outline-secondary', button !== primary);
  }
}

export function showDeployment(deployment) {
  current = deployment;
  const logo = document.getElementById('deployment-logo');
  logo.src = deployment.provider.logo;
  logo.alt = deployment.provider.name;
  document.getElementById('deployment-name').textContent = deployment.name;
  setState(null);
  document.getElementById('deployment-location').textContent = deployment.location
    ? `${text('ui.deployment.location')} ${deployment.location}`
    : '';
  list.replaceChildren(...deployment.resources.map(resource));
  show('view-deployment');
  check();
}

// the check: the rows of the plan and the buttons it allows; a page left meanwhile stays as it is
async function check() {
  const shown = current;
  const { password } = openedDeployment();
  checked = null;
  checkButton.disabled = true;
  deployButton.disabled = true;
  forceButton.disabled = true;
  setPrimary(checkButton);
  for (const led of list.querySelectorAll('.resource-state')) {
    led.classList.add('sak-led');
    led.dataset.sakState = 'busy';
  }
  setMessage(text('ui.deployment.checking'));
  try {
    const plan = await invoke('check_deployment', { name: shown.name, password, location: shown.location });
    if (current !== shown) {
      return;
    }
    checked = plan;
    list.replaceChildren(...plan.resources.map(row));
    const changes = plan.resources.filter((resource) => resource.resourceStatus !== 'up_to_date').length;
    setState(changes ? 'changed' : 'ok');
    deployButton.disabled = changes === 0;
    forceButton.disabled = false;
    setPrimary(changes ? deployButton : checkButton);
    setMessage(changes ? text('ui.deployment.changes').replaceAll('%{count}', changes) : text('ui.deployment.up_to_date'));
  } catch (error) {
    if (current === shown) {
      list.replaceChildren(...shown.resources.map(resource));
      setMessage(String(error), true);
    }
  } finally {
    if (current === shown) {
      checkButton.disabled = false;
    }
  }
}

function deploy(force) {
  const { password } = openedDeployment();
  runChecked(checked, current.name, password, force);
}

checkButton.addEventListener('click', check);
deployButton.addEventListener('click', () => deploy(false));
forceButton.addEventListener('click', () => deploy(true));
document.getElementById('deployment-destroy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'destroy' }),
);
document.getElementById('deployment-back').addEventListener('click', async () => {
  current = null;
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
});
