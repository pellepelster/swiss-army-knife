// the sak deployments found at the providers, each with the state of its resource group: the
// ssh key, the firewall and the server, what is missing and what changed. A deployment opens
// its page, create leads to a new deployment, back to the provider tokens
import { showCreate } from './create.js';
import { showDeployment } from './deployment.js';
import { show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const list = document.getElementById('deployments');
const status = document.getElementById('deployments-status');

// the state of a deployment as the led pill shows it, the diff status of a resource as its led
export const STATES = { ok: 'ok', incomplete: 'info', changed: 'warn' };
// the led of the theme for the diff status of a resource
const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

export function resource(state) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state.status;
  const mark = document.createElement('span');
  mark.className = 'resource-state sak-led';
  mark.dataset.sakState = LEDS[state.status] ?? 'neutral';
  mark.setAttribute('aria-hidden', 'true');
  const kind = document.createElement('span');
  kind.className = 'resource-kind';
  kind.textContent = text(`ui.deployments.resource.${state.kind}`);
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = state.name;
  item.append(mark, kind, name);
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  detail.textContent = state.status === 'missing' ? text('ui.deployments.missing') : (state.detail ?? '');
  item.append(detail);
  for (const change of state.changes) {
    const line = document.createElement('span');
    line.className = 'resource-change';
    line.textContent = change;
    item.append(line);
  }
  return item;
}

// the frontend of a deployment as `Address: <link>` at the bottom of its card, opened by rust in
// the browser of the system (the window itself never leaves the wizard)
function frontendLink(url) {
  const line = document.createElement('li');
  line.className = 'deployment-link';
  line.hidden = !url;
  const label = document.createElement('span');
  label.className = 'deployment-address';
  label.textContent = `${text('ui.deployments.address')}: `;
  const link = document.createElement('a');
  link.className = 'deployment-url sak-mono';
  link.href = url ?? '';
  link.textContent = url ?? '';
  link.addEventListener('click', (event) => {
    event.preventDefault();
    invoke('open_url', { url }).catch((error) => {
      status.textContent = String(error);
    });
  });
  line.append(label, link);
  return line;
}

function deployment(data) {
  const { provider, name, status: state, resources } = data;
  const item = document.createElement('li');
  item.className = 'card deployment';
  item.dataset.status = state;
  // the header opens the deployment
  const header = document.createElement('button');
  header.type = 'button';
  header.className = 'card-header deployment-header deployment-open provider';
  header.addEventListener('click', () => showDeployment(data));
  const logo = document.createElement('img');
  logo.className = 'provider-logo';
  logo.src = provider.logo;
  logo.alt = provider.name;
  const title = document.createElement('span');
  title.className = 'deployment-name';
  title.textContent = name;

  const badge = document.createElement('span');
  badge.className = 'deployment-status sak-status';
  badge.dataset.sakState = STATES[state] ?? 'neutral';
  badge.textContent = text(`ui.deployments.status.${state}`);
  header.append(logo, title, badge);
  const group = document.createElement('ul');
  group.className = 'resources card-body';
  group.replaceChildren(...resources.map(resource));
  // where its frontend is, opened in the browser of the system; none for a deployment without a
  // root domain on its resources
  // the last line of the list, so it starts where the resources start
  group.append(frontendLink(data.frontendUrl));
  item.append(header, group);
  return item;
}

function setStatus(message, failed = false) {
  status.textContent = message;
  status.classList.toggle('status-failed', failed);
}

export async function showDeployments() {
  list.replaceChildren();
  setStatus(text('ui.deployments.loading'));
  show('view-deployments');
  try {
    const deployments = await invoke('list_deployments');
    list.replaceChildren(...deployments.map(deployment));
    setStatus(deployments.length === 0 ? text('ui.deployments.empty') : '');
  } catch (error) {
    setStatus(String(error), true);
  }
}

document.getElementById('deployments-create').addEventListener('click', showCreate);
document.getElementById('deployments-back').addEventListener('click', async () => {
  const { showProviders } = await import('./providers.js');
  await showProviders();
});
