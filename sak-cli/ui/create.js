// the form of a create, a deploy of a new config and a destroy. Rust derives every key from the
// name and the password and plans; the plan shows in run.js. A destroy asks only whether to delete
// the data volume. A root domain that another deployment has in dns mode `domain` stops the plan;
// one with deployments in dns mode `subdomain` allows only that mode
import { openedDeployment } from './password.js';
import { runDestroy, showPlan } from './run.js';
import { navigate, setFailure, setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const nameInvalid = document.getElementById('create-name-invalid');
// why rust refused the name when its field was left, null for a valid name
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
const sourceHint = document.getElementById('create-source');
// a destroy uses the password the deployment was opened with
const destroyHidden = ['create-password-group', 'create-location-group', 'create-root-domain-group', 'create-dns-mode', 'create-user-mode'].map((id) => document.getElementById(id));
const multiUserGroups = ['create-admin-email-group', 'create-smtp'].map((id) => document.getElementById(id));
const userModeHint = document.getElementById('create-user-mode-hint');
const smtpInputs = Object.fromEntries(
  ['host', 'port', 'username', 'password', 'starttls', 'from'].map((field) => [field, document.getElementById(`create-smtp-${field}`)]),
);
// until the user gives a sender, it is noreply@<root domain> and follows the root domain
let senderGiven = false;

function defaultSender() {
  return rootDomainInput.value ? `noreply@${rootDomainInput.value}` : '';
}

function sender() {
  return smtpInputs.from.value.trim() || defaultSender();
}

function showDefaultSender() {
  if (!senderGiven) {
    smtpInputs.from.value = defaultSender();
  }
}

smtpInputs.from.addEventListener('input', () => {
  senderGiven = smtpInputs.from.value.trim() !== '';
});

// the data volume cannot be restored after a delete, so the form shows red
function showDeleteData() {
  createView.classList.toggle('danger-zone', intent === 'destroy' && deleteDataInput.checked);
}

deleteDataInput.addEventListener('change', showDeleteData);

// true to continue; Escape cancels
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
// the opened deployment, null for a new one; the intent is create, deploy (a new config) or destroy
let existing = null;
let intent = 'create';
// a deploy: the configuration the page of the deployment read, null when it read none
let loadedSettings = null;

// `<key>` for a create, `<key>_<intent>` else
function intentText(key) {
  return text(intent === 'create' ? key : `${key}_${intent}`);
}

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


function userMode() {
  return form.elements['user-mode'].value || 'single';
}

// a destroy needs no users
function multiUser() {
  return intent !== 'destroy' && userMode() === 'multi';
}

function showUserMode() {
  const multi = multiUser();
  for (const element of multiUserGroups) {
    element.hidden = !multi;
  }
  smtpInputs.host.required = multi;
  smtpInputs.port.required = multi;
  userModeHint.textContent = text(`ui.create.user_mode_hint_${userMode()}`);
  applyRootDomain();
}

for (const input of form.elements['user-mode']) {
  input.addEventListener('change', showUserMode);
}

// the admin's address is optional: the admin always signs in with the password
function multiUserIncomplete() {
  return multiUser() && (!smtpInputs.host.value.trim() || !smtpInputs.port.value);
}

for (const input of [adminEmailInput, smtpInputs.host, smtpInputs.port]) {
  input.addEventListener('input', () => applyRootDomain());
}

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
    from: smtpInputs.from.value.trim(),
  };
}

// rust sends the test e-mail from this machine, as the deployment will. Its answer stays until a
// field of the mail server changes
const smtpTest = document.getElementById('create-smtp-test');
const smtpStatus = document.getElementById('create-smtp-status');
const smtpTestDialog = document.getElementById('smtp-test');
const smtpTestTo = document.getElementById('smtp-test-to');

function showSmtpTest() {
  smtpTest.disabled = !smtpInputs.host.value.trim() || !smtpInputs.port.value || !sender();
}

for (const input of Object.values(smtpInputs)) {
  input.addEventListener('input', () => {
    setStatus(smtpStatus, '');
    showSmtpTest();
  });
  input.addEventListener('change', () => setStatus(smtpStatus, ''));
}

// null on cancel or escape
function askRecipient() {
  document.getElementById('smtp-test-text').textContent = text('ui.create.smtp_test_text', { from: sender() });
  smtpTestTo.value = smtpTestTo.value || adminEmailInput.value.trim();
  smtpTestDialog.returnValue = '';
  smtpTestDialog.showModal();
  smtpTestTo.focus();
  return new Promise((resolve) => {
    smtpTestDialog.addEventListener(
      'close',
      () => resolve(smtpTestDialog.returnValue === 'send' ? smtpTestTo.value.trim() : null),
      { once: true },
    );
  });
}

smtpTest.addEventListener('click', async () => {
  const to = await askRecipient();
  if (!to) {
    smtpTest.focus();
    return;
  }
  smtpTest.disabled = true;
  setStatus(smtpStatus, text('ui.create.smtp_sending'));
  try {
    const answer = await invoke('send_test_mail', {
      smtp: {
        host: smtpInputs.host.value.trim(),
        port: Number(smtpInputs.port.value) || 0,
        username: smtpInputs.username.value,
        password: smtpInputs.password.value,
        starttls: smtpInputs.starttls.checked,
        from: smtpInputs.from.value.trim(),
      },
      name: nameInput.value.trim(),
      rootDomain: rootDomainInput.value,
      to,
    });
    setStatus(smtpStatus, answer);
  } catch (error) {
    setFailure(smtpStatus, String(error));
  } finally {
    showSmtpTest();
  }
});

// the empty fields of the users take the values of the environment (SAK_USER_MODE, SAK_ADMIN_EMAIL,
// SAK_SMTP_*)
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
  if (defaults.smtpFrom) {
    smtpInputs.from.value = defaults.smtpFrom;
    senderGiven = true;
  }
}

// a deploy: the configuration the page of the deployment read fills the form, and a line says where
// it comes from and why the deployment did not answer
async function prefillNewConfig() {
  const settings = loadedSettings ?? {};
  if (settings.dnsMode) {
    setDnsMode(settings.dnsMode);
  }
  if (settings.rootDomain) {
    await loadRootDomains(settings.rootDomain);
  }
  if (settings.userMode) {
    form.elements['user-mode'].value = settings.userMode;
  }
  adminEmailInput.value = settings.adminEmail ?? '';
  if (settings.smtp) {
    smtpInputs.host.value = settings.smtp.host;
    smtpInputs.port.value = String(settings.smtp.port);
    smtpInputs.username.value = settings.smtp.username;
    smtpInputs.password.value = settings.smtp.password ?? '';
    smtpInputs.starttls.checked = settings.smtp.starttls;
    // a default sender follows the root domain as for a new deployment
    senderGiven = Boolean(settings.smtp.from) && settings.smtp.from !== defaultSender();
    smtpInputs.from.value = settings.smtp.from || defaultSender();
  }
  const values = { when: settings.deployedAt ?? '', error: settings.unread ?? '' };
  sourceHint.textContent = text(`ui.create.source_${settings.source ?? 'none'}`, values);
  sourceHint.hidden = false;
}

function dnsMode() {
  return form.elements['dns-mode'].value || 'subdomain';
}

function setDnsMode(mode) {
  form.elements['dns-mode'].value = mode;
  showDnsModeHint();
}

function showDnsModeHint() {
  const name = nameInput.value.trim() || '<name>';
  const hint = text(`ui.create.dns_mode_hint_${dnsMode()}`, { name });
  dnsModeHint.textContent = rootDomainInput.value ? hint.replaceAll('<root domain>', rootDomainInput.value) : hint;
}

nameInput.addEventListener('input', showDnsModeHint);

// an opened deployment keeps its name, unchecked
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

// the zones of the project, each with its possible dns modes
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

// a destroy ignores the root domain; else no possible dns mode, a refused name or a missing mail
// server disable the plan
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
  showDefaultSender();
  showSmtpTest();
}

rootDomainInput.addEventListener('change', applyRootDomain);
const status = document.getElementById('create-status');
const planButton = document.getElementById('create-plan');



// after the plan, the fields that the intent or the root domain disable stay disabled
function setBusy(busy) {
  for (const element of form.elements) {
    element.disabled = busy;
  }
  if (!busy) {
    locationInput.disabled = Boolean(existing);
    dnsModeGroup.disabled = intent === 'destroy';
    rootDomainInput.disabled = intent === 'destroy';
    applyRootDomain();
    showSmtpTest();
  }
}

// rust checks the repeat again; an opened deployment has no repeat, its password was checked on open
function passwordsMatch() {
  const match = existing ? true : passwordInput.value === repeatInput.value;
  repeatInput.classList.toggle('is-invalid', !match);
  return match;
}

for (const input of [passwordInput, repeatInput]) {
  input.addEventListener('input', () => repeatInput.classList.remove('is-invalid'));
}

// `chosen` when it is one of them, else the first one
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

// the empty fields take the values of the environment (SAK_NAME, SAK_PASSWORD, SAK_LOCATION and
// those of the users). An opened deployment gives its name, location, dns mode, root domain and the
// password it was opened with; a deploy the other settings from the configuration its page read
async function prefill() {
  const defaults = await invoke('deployment_defaults');
  if (existing) {
    nameInput.value = existing.name;
    passwordInput.value = openedDeployment()?.password ?? '';
    await loadLocations(existing.location);
    // a deployment without dns records is in dns mode `subdomain`
    setDnsMode(existing.dnsMode ?? 'subdomain');
    await loadRootDomains(existing.rootDomain);
    if (intent === 'deploy') {
      await prefillNewConfig();
    } else {
      prefillUsers(defaults);
    }
  } else {
    prefillUsers(defaults);
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

// the form always opens empty: nothing of an earlier create or destroy stays, not the ticked box of
// the data volume, not a password of the users
export async function showCreate(options = {}) {
  resetForm();
  existing = options.existing ?? null;
  intent = existing ? options.intent : 'create';
  loadedSettings = options.settings ?? null;
  nameRefusal = null;
  nameInput.classList.remove('is-invalid');
  document.getElementById('create-title').textContent = intentText('ui.create.title');
  document.getElementById('create-password-hint').textContent = intentText('ui.create.password_hint');
  repeatGroup.hidden = Boolean(existing);
  repeatInput.required = !existing;
  nameInput.readOnly = Boolean(existing);
  locationInput.disabled = Boolean(existing);
  // a deploy may change them
  dnsModeGroup.disabled = intent === 'destroy';
  rootDomainInput.disabled = intent === 'destroy';
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
    setFailure(status, String(error));
  }
  // hidden fields of the users must not stop the form of a destroy
  showUserMode();
  showSmtpTest();
  show('view-create');
  // a filled destroy focuses cancel, so enter never leads straight to its confirmation
  const filled = intent === 'destroy' ? document.getElementById('create-cancel') : planButton;
  (nameInput.value ? passwordInput.value ? filled : passwordInput : nameInput).focus();
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
    // cancel is busy too, so a cancel cannot slip in between the plan and the run
    setBusy(true);
    setStatus(status, text('ui.create.planning'));
    let plan;
    const deployment = newDeployment();
    try {
      plan = await invoke('plan_deployment', { deployment });
    } catch (error) {
      setFailure(status, String(error));
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
    setFailure(status, String(error));
  } finally {
    setBusy(false);
  }
});

// cancel of the form or of the plan of a new deployment
async function backToDeployments() {
  resetForm();
  await navigate('deployments');
}

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
  sourceHint.hidden = true;
  sourceHint.textContent = '';
  repeatGroup.hidden = false;
  repeatInput.required = true;
  existing = null;
  intent = 'create';
  loadedSettings = null;
  setStatus(status, '');
  setStatus(smtpStatus, '');
  smtpTestTo.value = '';
  senderGiven = false;
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
