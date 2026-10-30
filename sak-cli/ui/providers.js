// the provider tokens: the list, the selection of the provider for a new token and the entry of
// the token. Rust validates a token before it adds it, and gives it back only masked
import { showDeployments } from './deployments.js';
import { setFailure, setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

const form = document.getElementById('token-form');
const input = document.getElementById('token-input');
const status = document.getElementById('token-status');
let selected = null;

function provider(item, { logo, name }, detail) {
  const image = document.createElement('img');
  image.className = 'provider-logo';
  image.src = logo;
  image.alt = '';
  const label = document.createElement('span');
  label.className = 'provider-name';
  label.textContent = name;
  item.append(image, label);
  if (detail) {
    const token = document.createElement('code');
    token.className = 'provider-token';
    token.textContent = detail;
    item.append(token);
  }
  return item;
}

export async function showProviders() {
  const tokens = await invoke('list_provider_tokens');
  const list = document.getElementById('provider-tokens');
  list.replaceChildren(
    ...tokens.map((token) => {
      const item = document.createElement('li');
      item.className = 'list-group-item provider';
      return provider(item, token.provider, token.token);
    }),
  );
  document.getElementById('providers-empty').hidden = tokens.length > 0;
  // every token in the list is valid: rust adds no token it could not validate
  document.getElementById('providers-next').disabled = tokens.length === 0;
  show('view-providers');
}

async function showSelection() {
  const types = await invoke('list_provider_types');
  const list = document.getElementById('provider-types');
  list.replaceChildren(
    ...types.map((type) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'list-group-item list-group-item-action provider';
      button.addEventListener('click', () => showEntry(type));
      return provider(button, type);
    }),
  );
  show('view-select');
}

function showEntry(type) {
  selected = type;
  document.getElementById('token-logo').src = type.logo;
  document.getElementById('token-provider').textContent = type.name;
  document.getElementById('token-hint').textContent = type.tokenHint;
  input.value = '';
  setStatus(status, '');
  show('view-token');
  input.focus();
}


function setBusy(busy) {
  for (const element of form.elements) {
    element.disabled = busy;
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  setBusy(true);
  setStatus(status, text('ui.token.checking'));
  try {
    await invoke('add_provider_token', { provider: selected.id, token: input.value });
    input.value = '';
    await showProviders();
  } catch (error) {
    setFailure(status, String(error));
    input.focus();
  } finally {
    setBusy(false);
  }
});

document.getElementById('providers-add').addEventListener('click', showSelection);
document.getElementById('providers-next').addEventListener('click', showDeployments);
document.getElementById('select-cancel').addEventListener('click', showProviders);
document.getElementById('token-cancel').addEventListener('click', showProviders);
