// a new deployment: its name and password, from which rust derives the ssh key and plans the
// resources; the plan lists what creating it would do. Apply is not wired yet
import { show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const form = document.getElementById('create-form');
const nameInput = document.getElementById('create-name');
const passwordInput = document.getElementById('create-password');
const repeatInput = document.getElementById('create-password-repeat');
const status = document.getElementById('create-status');
const planList = document.getElementById('plan-resources');

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

// the password typed twice: the second field is marked until both match, rust checks it again
function passwordsMatch() {
  const match = passwordInput.value === repeatInput.value;
  repeatInput.classList.toggle('is-invalid', !match);
  return match;
}

for (const input of [passwordInput, repeatInput]) {
  input.addEventListener('input', () => repeatInput.classList.remove('is-invalid'));
}

export function showCreate() {
  setStatus('');
  repeatInput.classList.remove('is-invalid');
  show('view-create');
  nameInput.focus();
}

function showPlan(plan) {
  document.getElementById('plan-name').textContent = plan.name;
  planList.replaceChildren(...plan.resources.map(planned));
  show('view-plan');
}

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
    const plan = await invoke('plan_deployment', {
      name: nameInput.value,
      password: passwordInput.value,
      passwordRepeat: repeatInput.value,
    });
    setStatus('');
    showPlan(plan);
  } catch (error) {
    setStatus(String(error), true);
  } finally {
    setBusy(false);
  }
});

async function backToDeployments() {
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

document.getElementById('create-cancel').addEventListener('click', backToDeployments);
document.getElementById('plan-back').addEventListener('click', showCreate);
document.getElementById('plan-cancel').addEventListener('click', backToDeployments);
