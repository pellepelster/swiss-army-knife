// the footer of the window of a desktop app, which this module makes (<footer class="app-footer"> with
// #update-version, #update-button and #update-status, update.css): the version of the app and one button for the steps
// of an update: check, install, restart. Only a click asks the receiver, the line names it first. The
// commands version, check_update and install_update are the ones of sak-desktop-shared (update.rs),
// restart is the app's own
import { $, element, invoke, setFailure, setStatus, text } from './views.js';

// the footer below every view (DESKTOP-4), made here for every app at the end of the body
document.body.append(
  element('footer', { class: 'app-footer' }, [
    element('span', { id: 'update-version' }),
    element('button', { type: 'button', class: 'btn btn-outline-secondary btn-sm', id: 'update-button' }),
    element('span', { class: 'hint update-status', id: 'update-status', 'aria-live': 'polite' }),
  ]),
);
const status = $('update-status');
const button = $('update-button');
let app = '';
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
  show('check', text('common.update.checking', { app, receiver }), { busy: true });
  try {
    const answer = await invoke('check_update');
    newest = answer.newest;
    show(newest ? 'install' : 'check', answer.message);
  } catch (error) {
    show('check', String(error), { failed: true });
  }
}

async function install() {
  show('install', text('common.update.installing', { app, version: newest, receiver }), { busy: true });
  try {
    const answer = await invoke('install_update');
    if (answer.installed) {
      show('restart', text('ui.update.restart_hint', { app, version: answer.newest }));
    } else {
      newest = null;
      show('check', answer.message);
    }
  } catch (error) {
    show('install', String(error), { failed: true });
  }
}

// gone on success, the new version starts; the app refuses it while its work must not be stopped
async function restart() {
  try {
    await invoke('restart');
  } catch (error) {
    show('restart', String(error), { failed: true });
  }
}

button.addEventListener('click', () => ({ check, install, restart })[step]());

// never stops the start of the app: a footer that can not be filled says so and stays
export async function showVersion() {
  try {
    const answer = await invoke('version');
    app = answer.app;
    receiver = answer.receiver;
    $('update-version').textContent = text('ui.update.version', { app, version: answer.version });
    show('check', text('ui.update.idle', { app, receiver }));
  } catch (error) {
    button.disabled = true;
    setFailure(status, String(error));
  }
}
