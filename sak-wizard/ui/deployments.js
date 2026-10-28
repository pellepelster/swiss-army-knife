// the sak deployments found at the providers, each with the state of its resource group: the
// ssh key, the firewall and the server, what is missing and what changed. Create leads to a
// new deployment, back to the provider tokens
import { showCreate } from './create.js';
import { show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const list = document.getElementById('deployments');
const status = document.getElementById('deployments-status');

// the mark in front of a resource by its diff status
const MARKS = { up_to_date: '✓', missing: '✗', has_changes: '~', parent_missing: '✗', duplicate: '~' };

function resource(state) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state.status;
  const mark = document.createElement('span');
  mark.className = 'resource-state';
  mark.setAttribute('aria-hidden', 'true');
  mark.textContent = MARKS[state.status] ?? '?';
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

function deployment({ provider, name, status: state, resources }) {
  const item = document.createElement('li');
  item.className = 'deployment';
  item.dataset.status = state;
  const header = document.createElement('div');
  header.className = 'deployment-header';
  const logo = document.createElement('img');
  logo.className = 'provider-logo';
  logo.src = provider.logo;
  logo.alt = provider.name;
  const title = document.createElement('span');
  title.className = 'deployment-name';
  title.textContent = name;
  const badge = document.createElement('span');
  badge.className = 'deployment-status';
  badge.textContent = text(`ui.deployments.status.${state}`);
  header.append(logo, title, badge);
  const group = document.createElement('ul');
  group.className = 'resources';
  group.replaceChildren(...resources.map(resource));
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
