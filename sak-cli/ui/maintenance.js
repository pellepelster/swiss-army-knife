// the maintenance of the server of a deployment, from the menu of its page: the status, the update
// or the reboot, as `sakctl maintenance` has them. The view shows each line of the server while it
// runs, then the answer, warnings in their colour. There is no cancel, a command on the server is
// not stopped halfway; each has its timeout in rust. Back, once it ended, opens the page of the
// deployment again
import { openedDeployment } from './password.js';
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const progress = document.getElementById('maintenance-progress');
const answer = document.getElementById('maintenance-answer');
const status = document.getElementById('maintenance-status');
const backButton = document.getElementById('maintenance-back');
// the deployment of the maintenance that runs or ran last
let running = null;

function line(value, warn = false) {
  const item = document.createElement('li');
  item.className = 'maintenance-line';
  item.dataset.warn = String(warn);
  item.textContent = value;
  return item;
}

function setStatus(value, failed = false) {
  status.textContent = value;
  status.classList.toggle('status-failed', failed);
}

function finished() {
  backButton.disabled = false;
  backButton.focus();
}

// `action` is status, update or reboot
export async function showMaintenance(deployment, action) {
  const current = { deployment };
  running = current;
  const { password } = openedDeployment();
  document.getElementById('maintenance-title').textContent = text(`ui.maintenance.title_${action}`);
  document.getElementById('maintenance-name').textContent = deployment.name;
  progress.replaceChildren();
  answer.replaceChildren();
  backButton.disabled = true;
  setStatus(text(`ui.maintenance.running_${action}`));
  show('view-maintenance');
  const channel = new Channel();
  channel.onmessage = (message) => {
    if (running === current) {
      progress.append(line(message));
    }
  };
  try {
    const lines = await invoke('run_maintenance', { request: { action, name: deployment.name, password }, onLine: channel });
    if (running === current) {
      answer.replaceChildren(...lines.map(({ text: value, warn }) => line(value, warn)));
      setStatus(text('ui.maintenance.finished'));
    }
  } catch (error) {
    if (running === current) {
      setStatus(String(error), true);
    }
  } finally {
    if (running === current) {
      finished();
    }
  }
}

backButton.addEventListener('click', async () => {
  const { deployment } = running;
  running = null;
  const { showDeployment } = await import('./deployment.js');
  showDeployment(deployment);
});
