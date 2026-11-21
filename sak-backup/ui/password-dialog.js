// the dialog that asks for the password of a repository (BACKUP-3): once, or twice for a new
// repository; with an error when the one before was wrong; with a box to keep it in the keychain of
// this computer, never ticked by itself. It answers { password, remember }, or null when it is
// cancelled; nothing of it stays in the page
import { text } from './shared/views.js';

const { invoke } = window.__TAURI__.core;

const $ = (id) => document.getElementById(id);
const dialog = $('password-dialog');
const form = $('password-dialog-form');
const password = $('password-dialog-password');
const repeat = $('password-dialog-repeat');
const repeatField = $('password-dialog-repeat-field');
const error = $('password-dialog-error');
const remember = $('password-dialog-remember');

let answer = null;

// whether SAK_BACKUP_REPO_PASSWORD gives the password, so the window asks for none
let fromEnvironment = null;
export async function passwordFromEnvironment() {
  fromEnvironment ??= await invoke('env_password_set');
  return fromEnvironment;
}

function close(value) {
  answer?.(value);
  answer = null;
  password.value = '';
  repeat.value = '';
  remember.checked = false;
  dialog.close();
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (!password.value) {
    error.textContent = text('ui.plans.run_no_password');
    return;
  }
  if (!repeatField.hidden && password.value !== repeat.value) {
    error.textContent = text('ui.plans.run_passwords_differ');
    return;
  }
  close({ password: password.value, remember: remember.checked });
});

$('password-dialog-cancel').addEventListener('click', () => close(null));
// Escape cancels too
dialog.addEventListener('cancel', (event) => {
  event.preventDefault();
  close(null);
});
for (const input of [password, repeat]) {
  input.addEventListener('input', () => {
    error.textContent = '';
  });
}

// asks for the password: `title` and `action` as texts, `hint` above the field, `twice` for a new
// repository, `failure` the error of the password before
export function askPassword({ title, action, hint = '', twice = false, failure = '' }) {
  close(null);
  $('password-dialog-title').textContent = title;
  $('password-dialog-hint').textContent = hint;
  $('password-dialog-hint').hidden = !hint;
  $('password-dialog-submit').textContent = action;
  repeatField.hidden = !twice;
  error.textContent = failure;
  dialog.showModal();
  password.focus();
  return new Promise((resolve) => {
    answer = resolve;
  });
}
