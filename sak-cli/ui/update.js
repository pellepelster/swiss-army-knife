// the footer of the window: the version of sakctl, one button and one line that says what happens.
// The button goes through the steps of an update: check, update to the newer version, restart.
// Only a click asks github, the line names it first; while a step runs the button is disabled
import { text } from './views.js';

const { invoke } = window.__TAURI__.core;

const status = document.getElementById('update-status');
const button = document.getElementById('update-button');
let receiver = '';
// the step the button does next: check, install or restart
let step = 'check';
let newest = null;

// rust gives the texts as they are, the footer fills in their values
function fill(key, values = {}) {
  return Object.entries(values).reduce((filled, [name, value]) => filled.replaceAll(`%{${name}}`, value), text(key));
}

// the one line: the button of the next step and the text of what happens or happened
function show(next, message, { busy = false, failed = false } = {}) {
  step = next;
  button.textContent = {
    check: fill('ui.update.check'),
    install: fill('ui.update.install', { version: newest }),
    restart: fill('ui.update.restart'),
  }[next];
  button.classList.toggle('btn-primary', next === 'restart');
  button.classList.toggle('btn-outline-secondary', next !== 'restart');
  button.disabled = busy;
  status.textContent = message;
  status.title = message;
  status.classList.toggle('status-failed', failed);
}

async function check() {
  show('check', fill('shared.update.checking', { receiver }), { busy: true });
  try {
    const answer = await invoke('check_update');
    newest = answer.newest;
    show(newest ? 'install' : 'check', answer.message);
  } catch (error) {
    show('check', String(error), { failed: true });
  }
}

async function install() {
  show('install', fill('shared.update.installing', { version: newest, receiver }), { busy: true });
  try {
    const answer = await invoke('install_update');
    if (answer.installed) {
      show('restart', fill('ui.update.restart_hint', { version: answer.newest }));
    } else {
      newest = null;
      show('check', answer.message);
    }
  } catch (error) {
    show('install', String(error), { failed: true });
  }
}

// gone on success, the new sakctl starts; refused while a deployment is changed
async function restart() {
  try {
    await invoke('restart');
  } catch (error) {
    show('restart', String(error), { failed: true });
  }
}

button.addEventListener('click', () => ({ check, install, restart })[step]());

export async function showVersion() {
  const answer = await invoke('version');
  receiver = answer.receiver;
  document.getElementById('update-version').textContent = fill('ui.update.version', { version: answer.version });
  show('check', fill('ui.update.idle', { receiver }));
}
