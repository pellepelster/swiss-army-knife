// the footer of the window: the version of sakctl and one button for the steps of an update: check,
// install, restart. Only a click asks the receiver, the line names it first
import { setFailure, setStatus, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const status = document.getElementById('update-status');
const button = document.getElementById('update-button');
let receiver = '';
let step = 'check';
let newest = null;

function show(next, message, { busy = false, failed = false } = {}) {
  step = next;
  button.textContent = {
    check: text('ui.update.check'),
    install: text('ui.update.install', { version: newest }),
    restart: text('ui.update.restart'),
  }[next];
  button.classList.toggle('btn-primary', next === 'restart');
  button.classList.toggle('btn-outline-secondary', next !== 'restart');
  button.disabled = busy;
  (failed ? setFailure : setStatus)(status, message);
  status.title = message;
}

async function check() {
  show('check', text('common.update.checking', { receiver }), { busy: true });
  try {
    const answer = await invoke('check_update');
    newest = answer.newest;
    show(newest ? 'install' : 'check', answer.message);
  } catch (error) {
    show('check', String(error), { failed: true });
  }
}

async function install() {
  show('install', text('common.update.installing', { version: newest, receiver }), { busy: true });
  try {
    const answer = await invoke('install_update');
    if (answer.installed) {
      show('restart', text('ui.update.restart_hint', { version: answer.newest }));
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
  document.getElementById('update-version').textContent = text('ui.update.version', { version: answer.version });
  show('check', text('ui.update.idle', { receiver }));
}
