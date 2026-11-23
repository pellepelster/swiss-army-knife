// the page of one deployment, opened with its password. It reads the configuration once, from the
// running deployment, else from this machine; the check, the config form and the deploys use it.
// The check is a plan of a deploy as the deployment is. Without a configuration check and the
// deploys stay disabled, config… gives one. Force deploy runs the playbook again
import { showCreate } from './create.js';
import { STATES, frontendLink, resource } from './deployments.js';
import { showMaintenance } from './maintenance.js';
import { openedDeployment } from './password.js';
import { row, runChecked } from './run.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { menu } from './shared/menu.js';
import { currentVisit, invoke, isCurrent, navigate, register, setFailure, setStatus, show, text } from './shared/views.js';

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
let current = null;
// the plan of the last check; the run shows its rows
let checked = null;
// the configuration read when the page opened (`deployment_settings`), null while it is read or
// when it could not be
let settings = null;

// none before the check
function setState(state) {
  badge.hidden = !state;
  badge.dataset.sakState = STATES[state] ?? 'neutral';
  badge.textContent = state ? text(`common.deployments.status.${state}`) : '';
}

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

function hasConfig() {
  return settings?.source === 'running' || settings?.source === 'local';
}

function configRow(label, value, detail = '') {
  return resource({ label, name: value, detail });
}

// the password of the mail server: a mask of fixed length, so it does not tell the length, and its
// last three characters
function maskedPassword(password, passwordSet) {
  if (password) return `••••••••${password.length > 3 ? password.slice(-3) : ''}`;
  return passwordSet ? '••••••••' : text('ui.deployment.config.smtp_no_password');
}

function showConfig() {
  // the address of the frontend first (the list knows it from the resources)
  const rows = current?.frontendUrl ? [frontendLink(current.frontendUrl, configSource)] : [];
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
      const { host, port, username, password, passwordSet } = settings.smtp;
      rows.push(configRow(text('ui.create.smtp'), `${host}:${port}`));
      rows.push(configRow(text('ui.deployment.config.smtp_username'), username));
      rows.push(configRow(text('ui.deployment.config.smtp_password'), maskedPassword(password, passwordSet)));
      rows.push(configRow(text('ui.create.smtp_from'), settings.smtp.from || `noreply@${settings.rootDomain ?? ''}`));
    }
  }
  configList.replaceChildren(...rows);
  const values = { when: settings?.deployedAt ?? '', error: settings?.unread ?? '' };
  setStatus(configSource, text(`ui.deployment.config.source_${hasConfig() ? settings.source : 'none'}`, values));
}

// then the check, when there is a configuration to deploy with
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

// deploy and force deploy stay disabled until a check
function setButtons(checkable) {
  checkButton.disabled = !checkable;
  deployButton.disabled = true;
  forceButton.disabled = true;
  setPrimary(checkButton);
}

// a page left meanwhile stays as it is
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

// the menu of the maintenance (shared/menu.js): it closes itself after a click on an entry and gives
// the focus to its button, so the handler below starts with the menu closed
menu(maintenanceButton, maintenanceMenu);

function confirmReboot(name) {
  return confirmAction({
    title: text('ui.maintenance.confirm_title', { name }),
    message: text('ui.maintenance.confirm_text'),
    action: text('ui.maintenance.reboot'),
  });
}

maintenanceMenu.addEventListener('click', async (event) => {
  const item = event.target.closest('[data-action]');
  if (!item) {
    return;
  }
  const { action } = item.dataset;
  // a reboot that is cancelled leaves the focus on the button of the menu
  if (action === 'reboot' && !(await confirmReboot(current.name))) {
    return;
  }
  showMaintenance(current, action);
});

checkButton.addEventListener('click', check);
deployButton.addEventListener('click', () => deploy(false));
forceButton.addEventListener('click', () => deploy(true));
document.getElementById('deployment-destroy').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'destroy' }),
);
document.getElementById('deployment-edit-config').addEventListener('click', () =>
  showCreate({ existing: current, intent: 'deploy', settings }),
);
document.getElementById('deployment-back').addEventListener('click', async () => {
  current = null;
  settings = null;
  await navigate('deployments');
});

register('deployment', showDeployment);
