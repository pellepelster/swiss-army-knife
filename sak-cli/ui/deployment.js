// the page of one deployment, opened with its password: its state, its configuration and its
// resources. When it opens it reads the configuration once, from the running deployment, else from
// this machine; the check, the form of a new config and the deploys use what it read. With a
// configuration it checks the deployment: a deploy of the deployment as it is, planned in rust, its
// rows those of the plan, the provisioning included. Without one there is nothing to deploy with:
// check and the deploys stay disabled, new config gives one. The toolbar at the top right: destroy,
// which opens the form of a destroy, maintenance, a menu of status, update and reboot that each
// open the maintenance view (a reboot after a confirmation), new config, the form of a deploy with
// the configuration filled in, and check, which checks again. At the bottom right, one group: back
// to the list, force deploy (after a check, the playbook runs again) and deploy changes (only when
// the check found changes). The one green button is check, or deploy changes when there are changes
import { showCreate } from './create.js';
import { STATES, resource } from './deployments.js';
import { showMaintenance } from './maintenance.js';
import { openedDeployment } from './password.js';
import { row, runChecked } from './run.js';
import { currentVisit, isCurrent, navigate, register, setFailure, setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const message = document.getElementById('deployment-message');
const list = document.getElementById('deployment-resources');
const badge = document.getElementById('deployment-status');
const configSource = document.getElementById('deployment-config-source');
const configList = document.getElementById('deployment-config');
const checkButton = document.getElementById('deployment-check');
const deployButton = document.getElementById('deployment-deploy');
const forceButton = document.getElementById('deployment-force');
const maintenanceButton = document.getElementById('deployment-maintenance');
const maintenanceMenu = document.getElementById('deployment-maintenance-menu');
const rebootDialog = document.getElementById('reboot-confirm');
let current = null;
// the plan of the last check, its rows are those of the run
let checked = null;
// the configuration read when the page opened (`deployment_settings`), null while it is read or
// when it could not be
let settings = null;


// the state the check found; none before it
function setState(state) {
  badge.hidden = !state;
  badge.dataset.sakState = STATES[state] ?? 'neutral';
  badge.textContent = state ? text(`common.deployments.status.${state}`) : '';
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
  list.replaceChildren(...deployment.resources.map(resource));
  show('view-deployment');
  loadConfig();
}

// a configuration to deploy with: the running deployment answered it or this machine kept it
function hasConfig() {
  return settings?.source === 'running' || settings?.source === 'local';
}

// a row of the configuration, as a resource row: its label, its value and a detail
function configRow(label, value, detail = '') {
  return resource({ label, name: value, detail });
}

// what the configuration holds, as far as it is known
function showConfig() {
  const rows = [];
  if (settings?.rootDomain) {
    rows.push(configRow(text('ui.create.root_domain'), settings.rootDomain));
  }
  if (settings?.dnsMode) {
    rows.push(configRow(text('ui.create.dns_mode'), text(`ui.create.dns_mode_${settings.dnsMode}`)));
  }
  if (hasConfig() && settings.userMode) {
    rows.push(configRow(text('ui.create.user_mode'), text(`ui.create.user_mode_${settings.userMode}`)));
  }
  if (hasConfig() && settings.userMode === 'multi') {
    rows.push(configRow(text('ui.deployment.config.admin'), settings.adminEmail || text('ui.deployment.config.no_admin')));
    if (settings.smtp) {
      const { host, port, username } = settings.smtp;
      rows.push(configRow(text('ui.create.smtp'), `${host}:${port}`, username));
    }
  }
  configList.replaceChildren(...rows);
  const values = { when: settings?.deployedAt ?? '', error: settings?.unread ?? '' };
  setStatus(configSource, text(`ui.deployment.config.source_${hasConfig() ? settings.source : 'none'}`, values));
}

// the configuration, read once when the page opens; then the check, when there is one to deploy with
async function loadConfig() {
  const shown = current;
  const visit = currentVisit();
  const { password } = openedDeployment();
  settings = null;
  configList.replaceChildren();
  setStatus(configSource, text('ui.deployment.config.loading'));
  setButtons(false);
  setStatus(message, '');
  try {
    settings = await invoke('deployment_settings', { name: shown.name, password });
  } catch (error) {
    if (isCurrent(visit)) {
      setFailure(configSource, text('ui.deployment.config.not_read', { error: String(error) }));
    }
    return;
  }
  if (!isCurrent(visit)) {
    return;
  }
  showConfig();
  if (hasConfig()) {
    check();
  }
}

// check, force deploy and deploy changes need a configuration; deploy changes also changes
function setButtons(checkable) {
  checkButton.disabled = !checkable;
  deployButton.disabled = true;
  forceButton.disabled = true;
  setPrimary(checkButton);
}

// the check: the rows of the plan and the buttons it allows; a page left meanwhile stays as it is
async function check() {
  const shown = current;
  const visit = currentVisit();
  const { password } = openedDeployment();
  checked = null;
  setButtons(false);
  for (const led of list.querySelectorAll('.resource-state')) {
    led.classList.add('sak-led');
    led.dataset.sakState = 'busy';
  }
  setStatus(message, text('ui.deployment.checking'));
  try {
    const plan = await invoke('check_deployment', { name: shown.name, password, location: shown.location });
    if (!isCurrent(visit)) {
      return;
    }
    checked = plan;
    list.replaceChildren(...plan.resources.map(row));
    const changes = plan.resources.filter((resource) => resource.resourceStatus !== 'up_to_date').length;
    setState(changes ? 'changed' : 'ok');
    deployButton.disabled = changes === 0;
    forceButton.disabled = false;
    setPrimary(changes ? deployButton : checkButton);
    setStatus(message, changes ? text('ui.deployment.changes', { count: changes }) : text('ui.deployment.up_to_date'));
  } catch (error) {
    if (isCurrent(visit)) {
      list.replaceChildren(...shown.resources.map(resource));
      setFailure(message, String(error));
    }
  } finally {
    if (isCurrent(visit)) {
      checkButton.disabled = !hasConfig();
    }
  }
}

function deploy(force) {
  const { password } = openedDeployment();
  runChecked(checked, current.name, password, force);
}

// the menu of the maintenance opens below its button and closes on a choice, a click elsewhere and
// escape
function setMenu(open) {
  maintenanceMenu.classList.toggle('show', open);
  maintenanceButton.setAttribute('aria-expanded', String(open));
  // an open menu has the focus on its first entry, the arrow keys move it
  if (open) {
    menuItems()[0].focus();
  }
}

function menuItems() {
  return [...maintenanceMenu.querySelectorAll('[data-action]')];
}

maintenanceButton.addEventListener('click', (event) => {
  event.stopPropagation();
  setMenu(!maintenanceMenu.classList.contains('show'));
});
// arrow up and down, home and end move between the entries; tab leaves the menu closed
maintenanceMenu.addEventListener('keydown', (event) => {
  const items = menuItems();
  const at = items.indexOf(document.activeElement);
  const next = {
    ArrowDown: (at + 1) % items.length,
    ArrowUp: (at - 1 + items.length) % items.length,
    Home: 0,
    End: items.length - 1,
  }[event.key];
  if (next !== undefined) {
    event.preventDefault();
    items[next].focus();
  } else if (event.key === 'Tab') {
    setMenu(false);
  }
});
// whether the user confirms the reboot of the deployment `name`
function confirmReboot(name) {
  document.getElementById('reboot-confirm-title').textContent = text('ui.maintenance.confirm_title', { name });
  rebootDialog.returnValue = '';
  rebootDialog.showModal();
  return new Promise((resolve) => {
    rebootDialog.addEventListener('close', () => resolve(rebootDialog.returnValue === 'reboot'), { once: true });
  });
}

maintenanceMenu.addEventListener('click', async (event) => {
  const item = event.target.closest('[data-action]');
  if (!item) {
    return;
  }
  setMenu(false);
  const { action } = item.dataset;
  if (action === 'reboot' && !(await confirmReboot(current.name))) {
    // the entry that had the focus is hidden, the button of the menu takes it
    maintenanceButton.focus();
    return;
  }
  showMaintenance(current, action);
});
document.addEventListener('click', () => setMenu(false));
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && maintenanceMenu.classList.contains('show')) {
    setMenu(false);
    maintenanceButton.focus();
  }
});

checkButton.addEventListener('click', check);
deployButton.addEventListener('click', () => deploy(false));
forceButton.addEventListener('click', () => deploy(true));
document.getElementById('deployment-destroy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'destroy' }),
);
// the form of a deploy with a new config, filled in with the configuration read when the page
// opened; empty without one
document.getElementById('deployment-new-config').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'deploy', settings }),
);
document.getElementById('deployment-back').addEventListener('click', async () => {
  current = null;
  settings = null;
  await navigate('deployments');
});

register('deployment', showDeployment);
