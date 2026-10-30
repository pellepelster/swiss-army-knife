// the password of a deployment before its page opens. Rust checks it against the resources of the
// deployment while it is typed, a moment after the last key; next only with the right one, cancel
// back to the list. The password stays with the opened deployment for its check and its destroy.
// With SAK_PASSWORD set, the field starts with it and rust checks it at once
import { setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const form = document.getElementById('password-form');
const input = document.getElementById('password-input');
const status = document.getElementById('password-status');
const next = document.getElementById('password-next');
const toggle = document.getElementById('password-toggle');

// the password as dots, or as typed while the eye is pressed
function showTyped(typed) {
  input.type = typed ? 'text' : 'password';
  toggle.setAttribute('aria-pressed', String(typed));
  toggle.setAttribute('aria-label', text(typed ? 'ui.password.hide' : 'ui.password.show'));
}

toggle.addEventListener('click', () => {
  showTyped(input.type === 'password');
  input.focus();
});

// the wait after the last key before rust checks, each check derives the master key
const CHECK_AFTER_MS = 500;

let deployment = null;
let timer = null;
// what was asked last: an answer to an older password counts no more
let asked = '';
// the deployment that is open and its password
let opened = null;

export function openedDeployment() {
  return opened;
}

// a deployment open with its password, after the password screen or a create
export function openDeployment(data, password) {
  opened = { deployment: data, password };
}


export async function showPassword(data) {
  deployment = data;
  input.value = '';
  showTyped(false);
  next.disabled = true;
  setStatus(status, '');
  document.getElementById('password-name').textContent = data.name;
  show('view-password');
  input.focus();
  const { password } = await invoke('deployment_defaults');
  if (password && deployment === data && !input.value) {
    input.value = password;
    await check();
  }
}

async function check() {
  const password = input.value;
  asked = password;
  if (!password) {
    setStatus(status, '');
    return;
  }
  setStatus(status, text('ui.password.checking'));
  try {
    await invoke('check_deployment_password', { name: deployment.name, password });
    if (asked === password) {
      next.disabled = false;
      setStatus(status, text('ui.password.right'));
    }
  } catch (error) {
    if (asked === password) {
      setStatus(status, String(error), true);
    }
  }
}

input.addEventListener('input', () => {
  next.disabled = true;
  clearTimeout(timer);
  timer = setTimeout(check, CHECK_AFTER_MS);
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (next.disabled) {
    return;
  }
  openDeployment(deployment, input.value);
  input.value = '';
  const { showDeployment } = await import('./deployment.js');
  showDeployment(deployment);
});

document.getElementById('password-cancel').addEventListener('click', async () => {
  clearTimeout(timer);
  input.value = '';
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
});
