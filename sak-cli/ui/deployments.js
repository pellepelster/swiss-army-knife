// the sak deployments found at the providers, each with its resources and what they are: the
// fingerprint of a key, the size of a volume, the address of a server. No state: without the
// password the list sees only the labels, the page of a deployment checks it with its password. The header of a deployment asks for its password, then opens its page; create opens
// the form of a new deployment, back opens the provider tokens
import { showCreate } from './create.js';
import { showPassword } from './password.js';
import { setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const list = document.getElementById('deployments');
const status = document.getElementById('deployments-status');

// the led of the theme for the state of a deployment, on its pill on its page
export const STATES = { ok: 'ok', incomplete: 'info', changed: 'warn', unknown: 'danger' };

// a resource as the list knows it: its kind, its name and its detail. The place of the led stays
// empty, the page of the deployment puts the led of its check there
export function resource(state) {
  const item = document.createElement('li');
  item.className = 'resource';
  const mark = document.createElement('span');
  mark.className = 'resource-state';
  mark.setAttribute('aria-hidden', 'true');
  const kind = document.createElement('span');
  kind.className = 'resource-kind';
  kind.textContent = text(`shared.deployments.resource.${state.kind}`);
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = state.name;
  item.append(mark, kind, name);
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  detail.textContent = state.detail ?? '';
  item.append(detail);
  return item;
}

// the frontend of a deployment as `Address: <link>` at the bottom of its card. Rust opens the link
// in the browser of the system, the window never leaves its pages
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
    invoke('open_url', { url }).catch((error) => setStatus(status, String(error), true));
  });
  line.append(label, link);
  return line;
}

function deployment(data) {
  const { provider, name, resources } = data;
  const item = document.createElement('li');
  item.className = 'card deployment';
  // the header asks for the password of the deployment, then opens it
  const header = document.createElement('button');
  header.type = 'button';
  header.className = 'card-header deployment-header deployment-open provider';
  header.addEventListener('click', () => showPassword(data));
  const logo = document.createElement('img');
  logo.className = 'provider-logo';
  logo.src = provider.logo;
  logo.alt = provider.name;
  const title = document.createElement('span');
  title.className = 'deployment-name';
  title.textContent = name;
  header.append(logo, title);
  const group = document.createElement('ul');
  group.className = 'resources card-body';
  group.replaceChildren(...resources.map(resource));
  // a deployment the list could not compare says why; its page checks it again
  if (data.error) {
    const error = document.createElement('li');
    error.className = 'deployment-error status-failed';
    error.textContent = text('shared.deployments.not_compared', { name, error: data.error });
    group.append(error);
  }
  // the last line of the list, so it starts where the resources start; hidden for a deployment
  // without a root domain on its resources
  group.append(frontendLink(data.frontendUrl));
  item.append(header, group);
  return item;
}


export async function showDeployments() {
  list.replaceChildren();
  setStatus(status, text('ui.deployments.loading'));
  show('view-deployments');
  try {
    const deployments = await invoke('list_deployments');
    list.replaceChildren(...deployments.map(deployment));
    setStatus(status, deployments.length === 0 ? text('shared.deployments.empty') : '');
  } catch (error) {
    setStatus(status, String(error), true);
  }
}

document.getElementById('deployments-create').addEventListener('click', showCreate);
document.getElementById('deployments-back').addEventListener('click', async () => {
  const { showProviders } = await import('./providers.js');
  await showProviders();
});
