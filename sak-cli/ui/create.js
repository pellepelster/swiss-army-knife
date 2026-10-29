// the form to create, deploy or destroy a deployment, and its plan. A create takes the name and
// the password; rust derives every key of the deployment from them and plans the resources. The
// plan lists what the apply will do and shows each resource while rust works on it. After a
// create, the page of the new deployment opens. A deploy is the same for a deployment that
// exists: its name and location are fixed, the password is the one it was created with, and a
// server that must change is created anew. A destroy removes all of the deployment but the data
// volume, unless the user ticks the box to delete it too. The dns mode tells under which names
// of the root domain (a dns zone of the project) the apps are; a deploy can change both. A root
// domain that another deployment manages in dns mode `domain` stops the plan; one with
// deployments in dns mode `subdomain` allows only that mode
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const nameInvalid = document.getElementById('create-name-invalid');
// why rust refused the name when its field was left, null for a valid name; the plan waits for a
// valid name
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
const adminEmailInput = document.getElementById('create-admin-email');
// the fields a destroy hides: it asks only for the password and whether to delete the data volume
const destroyHidden = ['create-location-group', 'create-root-domain-group', 'create-dns-mode', 'create-user-mode'].map((id) => document.getElementById(id));
// what only multiple users need: the admin's address and the mail server
const multiUserGroups = ['create-admin-email-group', 'create-smtp'].map((id) => document.getElementById(id));
const userModeHint = document.getElementById('create-user-mode-hint');
const smtpInputs = Object.fromEntries(
  ['host', 'port', 'username', 'password', 'starttls'].map((field) => [field, document.getElementById(`create-smtp-${field}`)]),
);

// the data volume cannot be restored after a delete, so the form shows that in red
function showDeleteData() {
  createView.classList.toggle('danger-zone', intent === 'destroy' && deleteDataInput.checked);
}

deleteDataInput.addEventListener('change', showDeleteData);

// the last confirmation before a destroy: true to continue; Escape cancels
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
// the deployment to deploy or destroy, null for a new one, and the intent: create, deploy or
// destroy
let existing = null;
let intent = 'create';

// the text of `key` for the intent: `<key>` for a create, `<key>_deploy` or `<key>_destroy` else
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

// a redeploy takes what rust detects of the deployment, as the command line does: the user mode
// from its labels and, for multiple users, the admin and the mail server with its password from
// the running configuration (an accepted risk, see docs/ARCHITECTURE.md). The user mode is always
// set, the other fields only when they are empty. Rust is asked once for each password
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
    // a wrong password or a provider that is not reachable: nothing to take
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
  // an older backend gives no password. Then the user must type it again, else the redeploy
  // removes the password of the mail server
  const typeAgain = smtp.passwordSet && !smtpInputs.password.value;
  smtpPasswordHint.hidden = !typeAgain;
  smtpInputs.password.required = typeAgain;
  showUserMode();
}

passwordInput.addEventListener('blur', prefillFromRunning);

// the chosen user mode, single unless multi is chosen
function userMode() {
  return form.elements['user-mode'].value || 'single';
}

// true for multiple users on a create or a deploy; a destroy needs no users
function multiUser() {
  return intent !== 'destroy' && userMode() === 'multi';
}

// the fields of multiple users show and are required only for multiple users
function showUserMode() {
  const multi = multiUser();
  for (const element of multiUserGroups) {
    element.hidden = !multi;
  }
  adminEmailInput.required = multi;
  smtpInputs.host.required = multi;
  smtpInputs.port.required = multi;
  userModeHint.textContent = text(`ui.create.user_mode_hint_${userMode()}`);
  // the mode changes what the plan waits for
  applyRootDomain();
}

for (const input of form.elements['user-mode']) {
  input.addEventListener('change', showUserMode);
}

// true while multiple users miss the admin's address, the mail server, or its password where the
// running deployment has one; the plan waits for them
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

// the mail server as rust takes it, null unless multiple users
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

// the empty fields of the users take the values of the environment: SAK_USER_MODE,
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

// the chosen dns mode, subdomain unless domain is chosen
function dnsMode() {
  return form.elements['dns-mode'].value || 'subdomain';
}

function setDnsMode(mode) {
  form.elements['dns-mode'].value = mode;
  showDnsModeHint();
}

// the names of the apps in the chosen dns mode, with the name typed so far and the chosen root
// domain
function showDnsModeHint() {
  const name = nameInput.value.trim() || '<name>';
  const hint = text(`ui.create.dns_mode_hint_${dnsMode()}`).replaceAll('%{name}', name);
  dnsModeHint.textContent = rootDomainInput.value ? hint.replaceAll('<root domain>', rootDomainInput.value) : hint;
}

nameInput.addEventListener('input', showDnsModeHint);

// rust checks the name of a new deployment when its field is left; a deploy or destroy uses the
// name of the deployment as it is
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
// typing clears the refusal until the field is left again
nameInput.addEventListener('input', () => {
  if (nameRefusal) {
    nameRefusal = null;
    showName();
  }
});
dnsModeGroup.addEventListener('change', showDnsModeHint);

// the zones of the project that rust offers as root domains, each with its possible dns modes
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

// what the chosen root domain allows. A domain with no possible dns mode (or no zone at all)
// disables the plan, a domain with deployments in dns mode `subdomain` disables `domain`. A
// destroy ignores the root domain. A refused name or missing user fields also disable the plan
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
// the rows of the plan by their resource, for the events of the apply
const rows = new Map();

// the led of the theme for the diff status of a resource
const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

// a resource of the plan: its kind, name and resource status, then what the plan does to it.
// Rust decides the action and the working text (`ui.plan.<working>`) that the row shows when
// the apply starts
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

// every field is disabled while rust plans. After the plan, the fields that the intent or the
// root domain disable stay disabled
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

// a create needs the password twice: the repeat field is marked invalid until both match, and
// rust checks it again. A deploy or destroy has no repeat; rust checks the password against the
// deployment
function passwordsMatch() {
  const match = existing ? true : passwordInput.value === repeatInput.value;
  repeatInput.classList.toggle('is-invalid', !match);
  return match;
}

for (const input of [passwordInput, repeatInput]) {
  input.addEventListener('input', () => repeatInput.classList.remove('is-invalid'));
}

// the locations that rust offers; `chosen` when it is one of them, else the first one
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

// the empty fields take the values of the environment: SAK_NAME, SAK_PASSWORD, SAK_LOCATION and
// those of the users. A deploy or destroy takes its name, location, dns mode and root domain from
// the deployment
async function prefill(back) {
  const defaults = await invoke('deployment_defaults');
  prefillUsers(defaults);
  if (existing) {
    nameInput.value = existing.name;
    await loadLocations(existing.location);
    if (back) {
      // back from the plan: the chosen root domain stays
      await loadRootDomains(rootDomainInput.value);
    } else {
      // a deployment without a dns mode on its resources is in dns mode `subdomain`
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
  // a deploy or destroy has no repeat field; a value left in it from a create does not count
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
  // the password stays only when the user comes back from the plan
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
  // a destroy ignores the dns mode and the root domain
  dnsModeGroup.disabled = intent === 'destroy';
  rootDomainInput.disabled = intent === 'destroy';
  // the submit button of a destroy is red and has the name of the destroy
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
  // a destroy needs no users: their hidden fields must not stop the form
  showUserMode();
  if (!options.back) {
    runningAsked = null;
    smtpPasswordHint.hidden = true;
    smtpInputs.password.required = false;
  }
  show('view-create');
  // with a password from SAK_PASSWORD, read the running configuration now
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
  // the apply button of a destroy is red
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

// true when the playbook reports its tasks: then they show on the row of the provisioning, and
// the raw output of ansible shows only without them
let tasksReported = false;

// an event of the apply on the row of its resource: busy with the working text of the plan while
// rust works on it, ok when done, neutral when destroyed
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
  // the task that runs shows on the row of the provisioning
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

// what ansible prints while it provisions the server, line by line; the newest line stays in view
function onOutput(line) {
  planOutput.hidden = false;
  planOutput.textContent += `${line}\n`;
  planOutput.scrollTop = planOutput.scrollHeight;
}

// opens the page of a new deployment, as the list of the deployments finds it now. When the list
// does not have it, the plan stays and says that the deployment is up-to-date
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
    // the plan stays with its status
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

// cancel, from the form or from the plan: forgets what was typed
async function backToDeployments() {
  resetForm();
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

// forgets what was typed; the form is the form of a new deployment again
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
// back to the form of the same deployment and intent; what was typed stays
document.getElementById('plan-back').addEventListener('click', () => showCreate({ existing, intent, back: true }));
document.getElementById('plan-cancel').addEventListener('click', backToDeployments);
