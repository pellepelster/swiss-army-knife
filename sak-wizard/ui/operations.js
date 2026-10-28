// what the wizard does with the provider tokens: install SwissArmyKnife or upgrade it; neither
// is wired yet, back leads to the provider tokens
import { show } from './views.js';

export function showOperations() {
  show('view-operations');
}

document.getElementById('operations-back').addEventListener('click', async () => {
  const { showProviders } = await import('./providers.js');
  await showProviders();
});
