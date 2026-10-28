// what the wizard does with the provider tokens, the texts of each below ui.operations.<id>;
// none is wired yet, back leads to the provider tokens
import { show } from './views.js';

// line icons drawn in the color of the text around them, 24 units square
const OPERATIONS = {
  // an arrow down into a tray
  install: '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M4 17v3h16v-3"/>',
  // an arrow up in a circle
  upgrade: '<circle cx="12" cy="12" r="9"/><path d="m8 12 4-4 4 4"/><path d="M12 16V8"/>',
  // an archive box
  backup: '<rect x="3" y="4" width="18" height="4" rx="1"/><path d="M5 8v11h14V8"/><path d="M10 12h4"/>',
  // an arrow turning back
  restore: '<path d="M3 12a9 9 0 1 0 2.6-6.4"/><path d="M3 4v5h5"/>',
};

function icon(paths) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'choice-icon');
  svg.setAttribute('aria-hidden', 'true');
  // the paths are the constants above, nothing from outside
  svg.innerHTML = paths;
  return svg;
}

const list = document.getElementById('operations');
list.replaceChildren(
  ...Object.entries(OPERATIONS).map(([id, paths]) => {
    const name = document.createElement('span');
    name.className = 'choice-name';
    name.dataset.text = `ui.operations.${id}.name`;
    const description = document.createElement('span');
    description.className = 'hint';
    description.dataset.text = `ui.operations.${id}.description`;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'choice';
    button.id = `operation-${id}`;
    button.append(icon(paths), name, description);
    const item = document.createElement('li');
    item.append(button);
    return item;
  }),
);

export function showOperations() {
  show('view-operations');
}

document.getElementById('operations-back').addEventListener('click', async () => {
  const { showProviders } = await import('./providers.js');
  await showProviders();
});
