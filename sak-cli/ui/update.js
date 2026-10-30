// the footer of the window: the version of sakctl and its update. Only a click asks github, the
// button names it first; a newer release shows a second button that installs it, a successful
// install a third that restarts sakctl with the new version
import { text } from './views.js';

const { invoke } = window.__TAURI__.core;

const status = document.getElementById('update-status');
const checkButton = document.getElementById('update-check');
const installButton = document.getElementById('update-install');
const restartButton = document.getElementById('update-restart');
let receiver = '';
let newest = null;

function say(message, failed = false) {
  status.textContent = message;
  status.classList.toggle('status-failed', failed);
}

// rust gives the texts as they are, the footer fills in their values
function fill(key, values) {
  return Object.entries(values).reduce((filled, [name, value]) => filled.replaceAll(`%{${name}}`, value), text(key));
}

async function run(action, working) {
  checkButton.disabled = true;
  installButton.disabled = true;
  say(working);
  try {
    const answer = await invoke(action);
    say(answer.message);
    newest = answer.newest;
    // stays once sakctl is replaced, until the restart
    if (answer.installed) {
      restartButton.hidden = false;
    }
  } catch (error) {
    say(String(error), true);
  }
  checkButton.disabled = false;
  installButton.disabled = false;
  installButton.hidden = !newest;
  if (newest) {
    installButton.textContent = fill('ui.update.install', { version: newest });
  }
}

checkButton.addEventListener('click', () => run('check_update', fill('shared.update.checking', { receiver })));
installButton.addEventListener('click', () => run('install_update', fill('shared.update.installing', { version: newest, receiver })));
// gone on success, the new sakctl starts; refused while a deployment is changed
restartButton.addEventListener('click', async () => {
  try {
    await invoke('restart');
  } catch (error) {
    say(String(error), true);
  }
});

export async function showVersion() {
  const answer = await invoke('version');
  receiver = answer.receiver;
  document.getElementById('update-version').textContent = fill('ui.update.version', { version: answer.version });
  checkButton.textContent = fill('ui.update.check', { receiver });
}
