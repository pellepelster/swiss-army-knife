// a new deployment: its name and password, from which rust derives the ssh key and plans the
// resources; the plan lists what creating it would do and, on create, lights each resource up
// as rust creates it, then the deployments show the new one. A redeploy is the same for a
// deployment that exists: its name and location are given, the password is the one it was
// created with, and a server that must change is created anew
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const passwordInput = document.getElementById('create-password');
const repeatInput = document.getElementById('create-password-repeat');
const locationInput = document.getElementById('create-location');
const repeatGroup = document.getElementById('create-repeat-group');
const planApplyIcon = document.getElementById('plan-apply-icon');
// the deployment redeployed, none for a new one
let redeploy = null;

// what the form holds, as rust takes it to plan or create
function newDeployment() {
  return {
    name: nameInput.value,
    password: passwordInput.value,
    passwordRepeat: redeploy ? passwordInput.value : repeatInput.value,
    location: locationInput.value,
    intent: redeploy ? 'redeploy' : 'create',
  };
}
const status = document.getElementById('create-status');
const planButton = document.getElementById('create-plan');
const planList = document.getElementById('plan-resources');
const planStatus = document.getElementById('plan-status');
const planButtons = ['plan-cancel', 'plan-back', 'plan-apply'].map((id) => document.getElementById(id));
// the rows of the plan by the resource they show, for the events of the creation
const rows = new Map();

// the led of the theme for the diff status of a resource
const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

function planned({ resource, status: state, recreate, changes }) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state;
  const mark = document.createElement('span');
  mark.className = 'resource-state sak-led';
  mark.dataset.sakState = LEDS[state] ?? 'neutral';
  mark.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = resource;
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  detail.textContent = text(`ui.plan.${recreate ? 'recreate' : state}`);
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
// a redeploy checks it against the deployment instead
function passwordsMatch() {
  const match = redeploy ? true : passwordInput.value === repeatInput.value;
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
// SAK_LOCATION; a redeploy has its name and location from the deployment, only the password
// from the environment
async function prefill() {
  const defaults = await invoke('deployment_defaults');
  if (redeploy) {
    nameInput.value = redeploy.name;
    await loadLocations(redeploy.location);
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
  redeploy = options.redeploy ?? null;
  setStatus('');
  repeatInput.classList.remove('is-invalid');
  document.getElementById('create-title').textContent = text(redeploy ? 'ui.create.title_redeploy' : 'ui.create.title');
  document.getElementById('create-password-hint').textContent = text(
    redeploy ? 'ui.create.password_hint_redeploy' : 'ui.create.password_hint',
  );
  repeatGroup.hidden = Boolean(redeploy);
  repeatInput.required = !redeploy;
  nameInput.readOnly = Boolean(redeploy);
  locationInput.disabled = Boolean(redeploy);
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
  document.getElementById('plan-intro').textContent = text(redeploy ? 'ui.plan.intro_redeploy' : 'ui.plan.intro');
  document.getElementById('plan-apply-text').textContent = text(redeploy ? 'ui.plan.redeploy' : 'ui.plan.apply');
  planApplyIcon.hidden = Boolean(redeploy);
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
function onCreateEvent(message) {
  const row = rows.get(message.resource);
  if (!row) {
    return;
  }
  const led = row.querySelector('.resource-state');
  const detail = row.querySelector('.resource-detail');
  if (message.event === 'started') {
    led.dataset.sakState = 'busy';
    detail.textContent = text('ui.plan.working');
  } else if (message.event === 'applied') {
    led.dataset.sakState = 'ok';
    detail.textContent = text(`ui.plan.done.${message.applied}`);
  }
}

document.getElementById('plan-apply').addEventListener('click', async () => {
  for (const button of planButtons) {
    button.disabled = true;
  }
  setPlanStatus(text(redeploy ? 'ui.plan.redeploying' : 'ui.plan.creating'));
  const onEvent = new Channel();
  onEvent.onmessage = onCreateEvent;
  try {
    await invoke('create_deployment', { deployment: newDeployment(), onEvent });
    await backToDeployments();
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
  redeploy = null;
  setStatus('');
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
document.getElementById('plan-back').addEventListener('click', showCreate);
document.getElementById('plan-cancel').addEventListener('click', backToDeployments);
