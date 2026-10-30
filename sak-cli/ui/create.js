// the form of a new deployment and of a destroy. A create takes the name and the password; rust
// derives every key of the deployment from them and plans the resources, the plan shows what a
// deploy would do (run.js). A destroy takes the deployment as it was opened with its password,
// and asks only whether to delete the data volume; after a last confirmation it runs. The dns mode
// tells under which names of the root domain (a dns zone of the project) the apps are. A root
// domain that another deployment manages in dns mode `domain` stops the plan; one with
// deployments in dns mode `subdomain` allows only that mode
import { openedDeployment } from './password.js';
import { runDestroy, showPlan } from './run.js';
import { setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

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
// the fields a destroy hides: it asks only whether to delete the data volume, the password is the
// one the deployment was opened with
const destroyHidden = ['create-password-group', 'create-location-group', 'create-root-domain-group', 'create-dns-mode', 'create-user-mode'].map((id) => document.getElementById(id));
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
  document.getElementById('destroy-confirm-title').textContent = text('ui.confirm.title', { name });
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
// the deployment to destroy, null for a new one, and the intent: create or destroy
let existing = null;
let intent = 'create';

// the text of `key` for the intent: `<key>` for a create, `<key>_destroy` else
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


// the chosen user mode, single unless multi is chosen
function userMode() {
  return form.elements['user-mode'].value || 'single';
}

// true for multiple users on a create; a destroy needs no users
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

// true while multiple users miss the admin's address or the mail server; the plan waits for them
function multiUserIncomplete() {
  return multiUser() && (!adminEmailInput.value.trim() || !smtpInputs.host.value.trim() || !smtpInputs.port.value);
}

for (const input of [adminEmailInput, smtpInputs.host, smtpInputs.port]) {
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
  const hint = text(`ui.create.dns_mode_hint_${dnsMode()}`, { name });
  dnsModeHint.textContent = rootDomainInput.value ? hint.replaceAll('<root domain>', rootDomainInput.value) : hint;
}

nameInput.addEventListener('input', showDnsModeHint);

// rust checks the name of a new deployment when its field is left; a destroy uses the name of the
// deployment as it is
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
// rust checks it again. A destroy has no repeat, its password was checked when it was opened
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
// those of the users. A destroy takes its name, location, dns mode and root domain from the
// deployment, and the password it was opened with
async function prefill() {
  const defaults = await invoke('deployment_defaults');
  prefillUsers(defaults);
  if (existing) {
    nameInput.value = existing.name;
    passwordInput.value = openedDeployment()?.password ?? '';
    await loadLocations(existing.location);
    // a deployment without a dns mode on its resources is in dns mode `subdomain`
    setDnsMode(existing.dnsMode ?? 'subdomain');
    await loadRootDomains(existing.rootDomain);
  } else {
    await loadLocations(defaults.location);
    await loadRootDomains(rootDomainInput.value);
    if (!nameInput.value && defaults.name) {
      nameInput.value = defaults.name;
    }
  }
  if (!existing && !passwordInput.value && !repeatInput.value && defaults.password) {
    passwordInput.value = defaults.password;
    repeatInput.value = defaults.password;
  }
}

// the form, always empty when it opens: nothing of an earlier create or destroy stays, not the
// ticked box of the data volume, not a password of the users. Then the environment and, for a
// destroy, the deployment fill it
export async function showCreate(options = {}) {
  resetForm();
  existing = options.existing ?? null;
  intent = existing ? options.intent : 'create';
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
    await prefill();
  } catch (error) {
    setStatus(status, String(error), true);
  }
  // a destroy needs no users: their hidden fields must not stop the form
  showUserMode();
  show('view-create');
  (nameInput.value ? passwordInput.value ? planButton : passwordInput : nameInput).focus();
}


form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!passwordsMatch()) {
    setStatus(status, '');
    repeatInput.focus();
    return;
  }
  if (intent === 'destroy') {
    if (!(await confirmDestroy(nameInput.value))) {
      return;
    }
    // planned with the form busy, cancel too, as for a create: a cancel cannot slip in between
    // the plan and the run. A plan that fails stays on the form with its error
    setBusy(true);
    setStatus(status, text('ui.create.planning'));
    let plan;
    const deployment = newDeployment();
    try {
      plan = await invoke('plan_deployment', { deployment });
    } catch (error) {
      setStatus(status, String(error), true);
      return;
    } finally {
      setBusy(false);
    }
    setStatus(status, '');
    runDestroy(plan, deployment);
    return;
  }
  setBusy(true);
  setStatus(status, text('ui.create.planning'));
  try {
    const deployment = newDeployment();
    const plan = await invoke('plan_deployment', { deployment });
    setStatus(status, '');
    showPlan(plan, deployment, backToDeployments);
  } catch (error) {
    setStatus(status, String(error), true);
  } finally {
    setBusy(false);
  }
});

// cancel, from the form or from the plan of a new deployment: forgets what was typed
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
  setStatus(status, '');
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
