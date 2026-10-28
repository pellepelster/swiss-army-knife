// a new deployment: its name and password, from which rust derives the ssh key and plans the
// resources; the plan lists what creating it would do and, on create, lights each resource up
// as rust creates it, then the deployments show the new one. A deploy is the same for a
// deployment that exists: its name and location are given, the password is the one it was
// created with, and a server that must change is created anew; a destroy removes everything
// of it but the data volume
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const passwordInput = document.getElementById('create-password');
const repeatInput = document.getElementById('create-password-repeat');
const locationInput = document.getElementById('create-location');
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
    intent,
  };
}
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

// what the plan does to a resource: a destroy keeps the data volume and leaves what is gone
function plannedText(state, recreate, keep) {
  if (intent === 'destroy') {
    return keep ? 'keep' : state === 'missing' ? 'gone' : 'will_destroy';
  }
  return recreate ? 'recreate' : state;
}

function planned({ resource, status: state, recreate, keep, changes }) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state;
  item.dataset.recreate = String(Boolean(recreate));
  const mark = document.createElement('span');
  mark.className = 'resource-state sak-led';
  mark.dataset.sakState = LEDS[state] ?? 'neutral';
  mark.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = resource;
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  detail.textContent = text(`ui.plan.${plannedText(state, recreate, keep)}`);
  item.append(mark, name, detail);
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

function setBusy(busy) {
  for (const element of form.elements) {
    element.disabled = busy;
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
async function prefill() {
  const defaults = await invoke('deployment_defaults');
  if (existing) {
    nameInput.value = existing.name;
    await loadLocations(existing.location);
  } else {
    await loadLocations(defaults.location);
    if (!nameInput.value && defaults.name) {
      nameInput.value = defaults.name;
    }
  }
  if (!passwordInput.value && !repeatInput.value && defaults.password) {
    passwordInput.value = defaults.password;
    repeatInput.value = defaults.password;
  }
}

export async function showCreate(options = {}) {
  existing = options.existing ?? null;
  intent = existing ? options.intent : 'create';
  setStatus('');
  repeatInput.classList.remove('is-invalid');
  document.getElementById('create-title').textContent = intentText('ui.create.title');
  document.getElementById('create-password-hint').textContent = intentText('ui.create.password_hint');
  repeatGroup.hidden = Boolean(existing);
  repeatInput.required = !existing;
  nameInput.readOnly = Boolean(existing);
  locationInput.disabled = Boolean(existing);
  await prefill();
  show('view-create');
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

// what is being done to the resource of a row as its apply starts, after what the plan found:
// creating what is missing, updating or recreating what changed, checking what is up to date
function workingText(row) {
  if (intent === 'destroy') {
    return 'ui.plan.working_destroy';
  }
  const state = row.dataset.status;
  if (state === 'missing' || state === 'parent_missing') {
    return 'ui.plan.working';
  }
  if (state === 'up_to_date') {
    return 'ui.plan.working_check';
  }
  return row.dataset.recreate === 'true' ? 'ui.plan.working_recreate' : 'ui.plan.working_update';
}

// a resource of the plan as its creation reports it: busy while rust works on it, ok when done
function onCreateEvent(message) {
  if (message.event === 'provisioning') {
    onOutput(message.message);
    return;
  }
  if (message.event === 'output') {
    onOutput(message.line);
    return;
  }
  const row = rows.get(message.resource);
  if (!row) {
    return;
  }
  const led = row.querySelector('.resource-state');
  const detail = row.querySelector('.resource-detail');
  if (message.event === 'started') {
    led.dataset.sakState = 'busy';
    detail.textContent = text(workingText(row));
  } else if (message.event === 'applied') {
    led.dataset.sakState = 'ok';
    detail.textContent = text(`ui.plan.done.${message.applied}`);
  } else if (message.event === 'destroyed') {
    led.dataset.sakState = 'neutral';
    detail.textContent = text(message.gone ? 'ui.plan.done.destroyed' : 'ui.plan.gone');
  }
}

// what ansible prints while it provisions the server, line by line, the newest in sight
function onOutput(line) {
  planOutput.hidden = false;
  planOutput.textContent += `${line}\n`;
  planOutput.scrollTop = planOutput.scrollHeight;
}

applyButton.addEventListener('click', async () => {
  for (const button of planButtons) {
    button.disabled = true;
  }
  setPlanStatus(text({ create: 'ui.plan.creating', deploy: 'ui.plan.deploying', destroy: 'ui.plan.destroying' }[intent]));
  planOutput.textContent = '';
  planOutput.hidden = true;
  const onEvent = new Channel();
  onEvent.onmessage = onCreateEvent;
  try {
    await invoke('create_deployment', { deployment: newDeployment(), onEvent });
    setPlanStatus(text(intent === 'destroy' ? 'ui.plan.destroyed' : 'ui.plan.provisioned'));
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
  form.reset();
  repeatInput.classList.remove('is-invalid');
  nameInput.readOnly = false;
  locationInput.disabled = false;
  repeatGroup.hidden = false;
  repeatInput.required = true;
  existing = null;
  intent = 'create';
  setStatus('');
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
// back to the form of the same deployment and intent, what was typed stays
document.getElementById('plan-back').addEventListener('click', () => showCreate({ existing, intent }));
document.getElementById('plan-cancel').addEventListener('click', backToDeployments);
