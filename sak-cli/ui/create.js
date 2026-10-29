// a new deployment: its name and password, from which rust derives the ssh key and plans the
// resources; the plan lists what creating it would do and, on create, lights each resource up
// as rust creates it, then the deployments show the new one. A deploy is the same for a
// deployment that exists: its name and location are given, the password is the one it was
// created with, and a server that must change is created anew; a destroy removes everything
// of it but the data volume. The dns mode says under which names of the root domain (a dns
// zone of the project) the apps of the deployment are reached, a deploy may change both; a
// root domain another deployment manages in domain mode stops the form, one with subdomain
// deployments takes only the subdomain mode
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const nameInvalid = document.getElementById('create-name-invalid');
// why rust refused the name when its field was left, none for a valid one: the plan waits for one
let nameRefusal = null;
const passwordInput = document.getElementById('create-password');
const repeatInput = document.getElementById('create-password-repeat');
const locationInput = document.getElementById('create-location');
const rootDomainInput = document.getElementById('create-root-domain');
const rootDomainNote = document.getElementById('create-root-domain-note');
const dnsModeGroup = document.getElementById('create-dns-mode');
const domainModeInput = document.getElementById('create-dns-mode-domain');
const deleteDataGroup = document.getElementById('create-delete-data-group');
const deleteDataInput = document.getElementById('create-delete-data');
const createView = document.getElementById('view-create');
const confirmDialog = document.getElementById('destroy-confirm');
// a destroy asks for the name and the password alone
const adminEmailInput = document.getElementById('create-admin-email');
const destroyHidden = ['create-location-group', 'create-root-domain-group', 'create-dns-mode', 'create-user-mode'].map((id) => document.getElementById(id));
// what only multiple users need: the admin's address and the mail server
const multiUserGroups = ['create-admin-email-group', 'create-smtp'].map((id) => document.getElementById(id));
const userModeHint = document.getElementById('create-user-mode-hint');
const smtpInputs = Object.fromEntries(
  ['host', 'port', 'username', 'password', 'starttls'].map((field) => [field, document.getElementById(`create-smtp-${field}`)]),
);

// deleting the data volume is the one thing that cannot be undone, the form says so in red
function showDeleteData() {
  createView.classList.toggle('danger-zone', intent === 'destroy' && deleteDataInput.checked);
}

deleteDataInput.addEventListener('change', showDeleteData);

// the last word before a destroy, true when it is to go ahead; Escape is no
function confirmDestroy(name) {
  const withData = deleteDataInput.checked;
  document.getElementById('destroy-confirm-title').textContent = text('ui.confirm.title').replaceAll('%{name}', name);
  document.getElementById('destroy-confirm-text').textContent = text(withData ? 'ui.confirm.text_with_data' : 'ui.confirm.text');
  confirmDialog.classList.toggle('danger-zone', withData);
  confirmDialog.returnValue = '';
  confirmDialog.showModal();
  return new Promise((resolve) => {
    confirmDialog.addEventListener('close', () => resolve(confirmDialog.returnValue === 'destroy'), { once: true });
  });
}
const dnsModeHint = document.getElementById('create-dns-mode-hint');
const repeatGroup = document.getElementById('create-repeat-group');
const planApplyIcon = document.getElementById('plan-apply-icon');
// the deployment deployed or destroyed, none for a new one, and what is done with it:
// create, deploy or destroy
let existing = null;
let intent = 'create';

// the text of `key` for the intent: `ui.plan.intro` for a create, `ui.plan.intro_deploy`
function intentText(key) {
  return text(intent === 'create' ? key : `${key}_${intent}`);
}

// what the form holds, as rust takes it to plan or create
function newDeployment() {
  return {
    name: nameInput.value,
    password: passwordInput.value,
    passwordRepeat: existing ? passwordInput.value : repeatInput.value,
    location: locationInput.value,
    dnsMode: dnsMode(),
    rootDomain: rootDomainInput.value || null,
    intent,
    deleteData: intent === 'destroy' && deleteDataInput.checked,
    userMode: intent === 'destroy' ? null : userMode(),
    smtp: smtp(),
    adminEmail: multiUser() ? adminEmailInput.value.trim() : null,
  };
}

// a redeploy takes what rust detects of the deployment, as the command line does: the user mode on
// its labels and, for multiple users, the admin and the mail server with its password of the
// running configuration (an accepted risk, see docs/ARCHITECTURE.md); only fields still empty,
// asked once for each password
const smtpPasswordHint = document.getElementById('create-smtp-password-hint');
let runningAsked = null;

async function prefillFromRunning() {
  if (intent !== 'deploy' || !existing || !passwordInput.value || runningAsked === passwordInput.value) {
    return;
  }
  runningAsked = passwordInput.value;
  let detected;
  try {
    detected = await invoke('detected_settings', { name: existing.name, password: passwordInput.value });
  } catch {
    // another password or the provider not reachable: nothing to take over
    return;
  }
  if (detected.userMode) {
    form.elements['user-mode'].value = detected.userMode;
  }
  if (!adminEmailInput.value && detected.adminEmail) {
    adminEmailInput.value = detected.adminEmail;
  }
  const smtp = detected.smtp;
  if (!smtp) {
    showUserMode();
    return;
  }
  if (!smtpInputs.host.value) {
    smtpInputs.host.value = smtp.host;
    smtpInputs.port.value = smtp.port;
    smtpInputs.username.value = smtp.username;
    smtpInputs.starttls.checked = smtp.starttls;
  }
  if (!smtpInputs.password.value && smtp.password) {
    smtpInputs.password.value = smtp.password;
  }
  // a backend of before answers no password: then it has to be typed again, a redeploy without it
  // would leave the mail server of the backend without its password
  const typeAgain = smtp.passwordSet && !smtpInputs.password.value;
  smtpPasswordHint.hidden = !typeAgain;
  smtpInputs.password.required = typeAgain;
  showUserMode();
}

passwordInput.addEventListener('blur', prefillFromRunning);

// the user mode chosen, single unless multi is
function userMode() {
  return form.elements['user-mode'].value || 'single';
}

// multiple users for a create or a deploy, a destroy needs no users
function multiUser() {
  return intent !== 'destroy' && userMode() === 'multi';
}

// the fields of multiple users show and are required only for them
function showUserMode() {
  const multi = multiUser();
  for (const element of multiUserGroups) {
    element.hidden = !multi;
  }
  adminEmailInput.required = multi;
  smtpInputs.host.required = multi;
  smtpInputs.port.required = multi;
  userModeHint.textContent = text(`ui.create.user_mode_hint_${userMode()}`);
  // what the plan waits for changes with the mode
  applyRootDomain();
}

for (const input of form.elements['user-mode']) {
  input.addEventListener('change', showUserMode);
}

// multiple users need the admin's address and the mail server, and its password where the running
// deployment has one: without them the plan waits
function multiUserIncomplete() {
  return (
    multiUser() &&
    (!adminEmailInput.value.trim() ||
      !smtpInputs.host.value.trim() ||
      !smtpInputs.port.value ||
      (smtpInputs.password.required && !smtpInputs.password.value))
  );
}

for (const input of [adminEmailInput, smtpInputs.host, smtpInputs.port, smtpInputs.password]) {
  input.addEventListener('input', () => applyRootDomain());
}

// the mail server as rust takes it, only for multiple users
function smtp() {
  const host = smtpInputs.host.value.trim();
  if (!multiUser()) {
    return null;
  }
  return {
    host,
    port: Number(smtpInputs.port.value) || 0,
    username: smtpInputs.username.value,
    password: smtpInputs.password.value,
    starttls: smtpInputs.starttls.checked,
  };
}

// the fields of the users still empty take what the environment proposes, SAK_USER_MODE,
// SAK_ADMIN_EMAIL, SAK_SMTP_HOST, SAK_SMTP_PORT, SAK_SMTP_USERNAME, SAK_SMTP_PASSWORD and
// SAK_SMTP_STARTTLS
function prefillUsers(defaults) {
  if (defaults.userMode && !adminEmailInput.value && !smtpInputs.host.value) {
    form.elements['user-mode'].value = defaults.userMode;
  }
  if (!adminEmailInput.value) {
    adminEmailInput.value = defaults.adminEmail ?? '';
  }
  if (smtpInputs.host.value) {
    return;
  }
  smtpInputs.host.value = defaults.smtpHost ?? '';
  smtpInputs.port.value = defaults.smtpPort ?? '587';
  smtpInputs.username.value = defaults.smtpUsername ?? '';
  smtpInputs.password.value = defaults.smtpPassword ?? '';
  smtpInputs.starttls.checked = defaults.smtpStarttls ?? true;
}

// the dns mode chosen, subdomain unless domain is
function dnsMode() {
  return form.elements['dns-mode'].value || 'subdomain';
}

function setDnsMode(mode) {
  form.elements['dns-mode'].value = mode;
  showDnsModeHint();
}

// how the names of the apps are made in the chosen mode, with the name typed so far and the
// root domain chosen
function showDnsModeHint() {
  const name = nameInput.value.trim() || '<name>';
  const hint = text(`ui.create.dns_mode_hint_${dnsMode()}`).replaceAll('%{name}', name);
  dnsModeHint.textContent = rootDomainInput.value ? hint.replaceAll('<root domain>', rootDomainInput.value) : hint;
}

nameInput.addEventListener('input', showDnsModeHint);

// the name of a new deployment is checked by rust when its field is left; a deploy or destroy
// takes the name of the deployment as it is
async function checkName() {
  nameRefusal = null;
  if (!existing) {
    try {
      await invoke('check_name', { name: nameInput.value });
    } catch (error) {
      nameRefusal = String(error);
    }
  }
  showName();
}

function showName() {
  nameInput.classList.toggle('is-invalid', Boolean(nameRefusal));
  nameInvalid.textContent = nameRefusal ?? '';
  applyRootDomain();
}

nameInput.addEventListener('blur', checkName);
// typing again takes the refusal back until the field is left again
nameInput.addEventListener('input', () => {
  if (nameRefusal) {
    nameRefusal = null;
    showName();
  }
});
dnsModeGroup.addEventListener('change', showDnsModeHint);

// the zones of the project rust offers as root domains, each with the dns modes possible
let rootDomains = [];

async function loadRootDomains(chosen) {
  rootDomains = await invoke('root_domains', { name: existing?.name ?? nameInput.value });
  rootDomainInput.replaceChildren(
    ...rootDomains.map(({ name }) => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      return option;
    }),
  );
  if (chosen && rootDomains.some(({ name }) => name === chosen)) {
    rootDomainInput.value = chosen;
  }
  applyRootDomain();
}

// what the chosen root domain allows: no zone or a domain no other deployment can use stops
// the plan, a domain with subdomain deployments leaves only the subdomain mode; a destroy
// does not care
function applyRootDomain() {
  const domain = rootDomains.find(({ name }) => name === rootDomainInput.value);
  const destroying = intent === 'destroy';
  const note = rootDomains.length === 0 ? text('ui.create.no_zones') : domain?.note;
  rootDomainNote.textContent = note ?? '';
  rootDomainNote.hidden = destroying || !note;
  const modes = domain?.dnsModes ?? [];
  domainModeInput.disabled = !destroying && !modes.includes('domain');
  if (domainModeInput.disabled && dnsMode() === 'domain') {
    setDnsMode('subdomain');
  }
  planButton.disabled = (!destroying && modes.length === 0) || Boolean(nameRefusal) || multiUserIncomplete();
  showDnsModeHint();
}

rootDomainInput.addEventListener('change', applyRootDomain);
const status = document.getElementById('create-status');
const planButton = document.getElementById('create-plan');
const planList = document.getElementById('plan-resources');
const planStatus = document.getElementById('plan-status');
const planOutput = document.getElementById('plan-output');
const applyButton = document.getElementById('plan-apply');
const planButtons = ['plan-cancel', 'plan-back', 'plan-apply'].map((id) => document.getElementById(id));
// the rows of the plan by the resource they show, for the events of the creation
const rows = new Map();

// the led of the theme for the diff status of a resource
const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

// a resource of the plan: its kind and name, its status (up-to-date, missing, has changes), then
// what the plan does to it, which rust decides
function planned({ kind, name: resourceName, resourceStatus, action, working, status: state, changes }) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state;
  item.dataset.working = working;
  const mark = document.createElement('span');
  mark.className = 'resource-state sak-led';
  mark.dataset.sakState = LEDS[state] ?? 'neutral';
  mark.setAttribute('aria-hidden', 'true');
  const kindLabel = document.createElement('span');
  kindLabel.className = 'resource-kind';
  kindLabel.textContent = text(`ui.deployments.resource.${kind}`);
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = resourceName;
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  const status = text(`ui.resource_status.${resourceStatus}`);
  detail.textContent = action ? `${status}, ${text(`ui.plan.${action}`)}` : status;
  item.append(mark, kindLabel, name, detail);
  for (const change of changes) {
    const line = document.createElement('span');
    line.className = 'resource-change';
    line.textContent = change;
    item.append(line);
  }
  return item;
}

function setStatus(message, failed = false) {
  status.textContent = message;
  status.classList.toggle('status-failed', failed);
}

// every field off while rust plans; back on, those the deployment and the root domain keep
// off stay off
function setBusy(busy) {
  for (const element of form.elements) {
    element.disabled = busy;
  }
  if (!busy) {
    locationInput.disabled = Boolean(existing);
    dnsModeGroup.disabled = intent === 'destroy';
    rootDomainInput.disabled = intent === 'destroy';
    applyRootDomain();
  }
}

// the password typed twice: the second field is marked until both match, rust checks it again;
// a deploy or destroy checks it against the deployment instead
function passwordsMatch() {
  const match = existing ? true : passwordInput.value === repeatInput.value;
  repeatInput.classList.toggle('is-invalid', !match);
  return match;
}

for (const input of [passwordInput, repeatInput]) {
  input.addEventListener('input', () => repeatInput.classList.remove('is-invalid'));
}

// the locations rust offers, the first one chosen unless the environment says otherwise
async function loadLocations(chosen) {
  if (locationInput.options.length === 0) {
    const locations = await invoke('locations');
    locationInput.replaceChildren(
      ...locations.map(({ id, name }) => {
        const option = document.createElement('option');
        option.value = id;
        option.textContent = `${name} (${id})`;
        return option;
      }),
    );
  }
  if (chosen && [...locationInput.options].some((option) => option.value === chosen)) {
    locationInput.value = chosen;
  }
}

// the fields still empty take what the environment proposes, SAK_NAME, SAK_PASSWORD and
// SAK_LOCATION; a deploy or destroy has its name and location from the deployment, only the password
// from the environment
async function prefill(back) {
  const defaults = await invoke('deployment_defaults');
  prefillUsers(defaults);
  if (existing) {
    nameInput.value = existing.name;
    await loadLocations(existing.location);
    if (back) {
      // back from the plan, what was chosen stays
      await loadRootDomains(rootDomainInput.value);
    } else {
      // a deployment created before the dns mode was kept is a subdomain one
      setDnsMode(existing.dnsMode ?? 'subdomain');
      await loadRootDomains(existing.rootDomain);
    }
  } else {
    await loadLocations(defaults.location);
    await loadRootDomains(rootDomainInput.value);
    if (!nameInput.value && defaults.name) {
      nameInput.value = defaults.name;
    }
  }
  // a deploy or destroy has no repeat field, what it still holds of a create does not count
  if (!passwordInput.value && (existing || !repeatInput.value) && defaults.password) {
    passwordInput.value = defaults.password;
    repeatInput.value = defaults.password;
  }
}

export async function showCreate(options = {}) {
  existing = options.existing ?? null;
  intent = existing ? options.intent : 'create';
  setStatus('');
  repeatInput.classList.remove('is-invalid');
  // the password of another deployment does not stay, back from the plan it does
  if (!options.back) {
    passwordInput.value = '';
    repeatInput.value = '';
  }
  nameRefusal = null;
  nameInput.classList.remove('is-invalid');
  document.getElementById('create-title').textContent = intentText('ui.create.title');
  document.getElementById('create-password-hint').textContent = intentText('ui.create.password_hint');
  repeatGroup.hidden = Boolean(existing);
  repeatInput.required = !existing;
  nameInput.readOnly = Boolean(existing);
  locationInput.disabled = Boolean(existing);
  // a destroy removes the deployment whatever its dns mode and root domain
  dnsModeGroup.disabled = intent === 'destroy';
  rootDomainInput.disabled = intent === 'destroy';
  // the submit of a destroy is a destructive button, red and named for what it leads to
  planButton.textContent = text(intent === 'destroy' ? 'ui.plan.destroy' : 'ui.create.plan');
  planButton.classList.toggle('btn-danger', intent === 'destroy');
  planButton.classList.toggle('btn-primary', intent !== 'destroy');
  deleteDataGroup.hidden = intent !== 'destroy';
  for (const element of destroyHidden) {
    element.hidden = intent === 'destroy';
  }
  showDeleteData();
  try {
    await prefill(Boolean(options.back));
  } catch (error) {
    setStatus(String(error), true);
  }
  // a destroy needs no users, their hidden fields must not stop the form
  showUserMode();
  if (!options.back) {
    runningAsked = null;
    smtpPasswordHint.hidden = true;
    smtpInputs.password.required = false;
  }
  show('view-create');
  // with the password at hand (SAK_PASSWORD), the running configuration right away
  prefillFromRunning();
  (nameInput.value ? passwordInput.value ? planButton : passwordInput : nameInput).focus();
}

function setPlanStatus(message, failed = false) {
  planStatus.textContent = message;
  planStatus.classList.toggle('status-failed', failed);
}

function showPlan(plan) {
  document.getElementById('plan-name').textContent = plan.name;
  document.getElementById('plan-intro').textContent = intentText('ui.plan.intro');
  document.getElementById('plan-apply-text').textContent = text(intent === 'create' ? 'ui.plan.apply' : `ui.plan.${intent}`);
  planApplyIcon.hidden = intent !== 'create';
  // a destroy is the one action that takes something away, its button says so
  applyButton.classList.toggle('btn-danger', intent === 'destroy');
  applyButton.classList.toggle('btn-primary', intent !== 'destroy');
  rows.clear();
  planList.replaceChildren(
    ...plan.resources.map((state) => {
      const item = planned(state);
      rows.set(state.resource, item);
      return item;
    }),
  );
  setPlanStatus('');
  show('view-plan');
}

// a resource of the plan as its creation reports it: busy while rust works on it, ok when done
// whether the playbook reported its tasks: then they show on the row of the provisioning, the raw
// output of ansible only without them
let tasksReported = false;

function onCreateEvent(message) {
  if (message.event === 'provisioning') {
    onOutput(message.message);
    return;
  }
  if (message.event === 'output') {
    if (!tasksReported) {
      onOutput(message.line);
    }
    return;
  }
  const row = rows.get(message.resource);
  if (!row) {
    return;
  }
  const led = row.querySelector('.resource-state');
  const detail = row.querySelector('.resource-detail');
  // the task that runs on the label of the provisioning, at the end all ok or the failed task
  if (message.event === 'taskStarted' || message.event === 'taskDone') {
    if (!tasksReported) {
      tasksReported = true;
      planOutput.hidden = true;
    }
    if (message.event === 'taskStarted') {
      led.dataset.sakState = 'busy';
      detail.textContent = message.task;
    }
    return;
  }
  if (message.event === 'provisioned') {
    led.dataset.sakState = 'ok';
    detail.dataset.outcome = 'ok';
    detail.textContent = text('ui.plan.provisioning.all_ok');
    return;
  }
  if (message.event === 'provisionFailed') {
    led.dataset.sakState = 'danger';
    detail.dataset.outcome = 'failed';
    detail.textContent = message.task
      ? text('ui.plan.provisioning.failed').replaceAll('%{task}', message.task).replaceAll('%{message}', message.message)
      : message.message;
    return;
  }
  if (message.event === 'started') {
    led.dataset.sakState = 'busy';
    detail.textContent = text(`ui.plan.${row.dataset.working}`);
  } else if (message.event === 'applied') {
    led.dataset.sakState = 'ok';
    detail.textContent = text(`ui.plan.done.${message.applied}`);
  } else if (message.event === 'destroyed') {
    led.dataset.sakState = 'neutral';
    detail.textContent = text(message.gone ? 'ui.plan.done.destroyed' : 'ui.plan.already_deleted');
  }
}

// what ansible prints while it provisions the server, line by line, the newest in sight
function onOutput(line) {
  planOutput.hidden = false;
  planOutput.textContent += `${line}\n`;
  planOutput.scrollTop = planOutput.scrollHeight;
}

// a deployment just created: its page, as the list of the deployments finds it now; without it the
// plan stays, it says the deployment is up-to-date
async function showCreated(name) {
  try {
    const deployments = await invoke('list_deployments');
    const created = deployments.find((deployment) => deployment.name === name);
    if (created) {
      resetForm();
      const { showDeployment } = await import('./deployment.js');
      showDeployment(created);
    }
  } catch {
    // the plan with its status stays
  }
}

applyButton.addEventListener('click', async () => {
  if (intent === 'destroy' && !(await confirmDestroy(nameInput.value))) {
    return;
  }
  for (const button of planButtons) {
    button.disabled = true;
  }
  const withData = intent === 'destroy' && deleteDataInput.checked ? '_with_data' : '';
  setPlanStatus(text({ create: 'ui.plan.creating', deploy: 'ui.plan.deploying', destroy: `ui.plan.destroying${withData}` }[intent]));
  planOutput.textContent = '';
  planOutput.hidden = true;
  tasksReported = false;
  const onEvent = new Channel();
  onEvent.onmessage = onCreateEvent;
  try {
    await invoke('create_deployment', { deployment: newDeployment(), onEvent });
    setPlanStatus(text(intent === 'destroy' ? `ui.plan.destroyed${withData}` : 'ui.plan.provisioned'));
    if (intent === 'create') {
      await showCreated(nameInput.value.trim());
    }
  } catch (error) {
    setPlanStatus(String(error), true);
  } finally {
    for (const button of planButtons) {
      button.disabled = false;
    }
  }
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!passwordsMatch()) {
    setStatus('');
    repeatInput.focus();
    return;
  }
  setBusy(true);
  setStatus(text('ui.create.planning'));
  try {
    const plan = await invoke('plan_deployment', { deployment: newDeployment() });
    setStatus('');
    showPlan(plan);
  } catch (error) {
    setStatus(String(error), true);
  } finally {
    setBusy(false);
  }
});

// cancel, from the form or from the plan: what was typed is forgotten, back from the plan keeps it
async function backToDeployments() {
  resetForm();
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

// what was typed is forgotten, the form is the one of a new deployment again
function resetForm() {
  form.reset();
  repeatInput.classList.remove('is-invalid');
  nameInput.readOnly = false;
  locationInput.disabled = false;
  dnsModeGroup.disabled = false;
  rootDomainInput.disabled = false;
  for (const element of destroyHidden) {
    element.hidden = false;
  }
  createView.classList.remove('danger-zone');
  repeatGroup.hidden = false;
  repeatInput.required = true;
  existing = null;
  intent = 'create';
  setStatus('');
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
// back to the form of the same deployment and intent, what was typed stays
document.getElementById('plan-back').addEventListener('click', () => showCreate({ existing, intent, back: true }));
document.getElementById('plan-cancel').addEventListener('click', backToDeployments);
