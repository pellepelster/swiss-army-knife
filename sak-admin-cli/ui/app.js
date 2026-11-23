// the checks first, then the provider tokens when there is none, else the deployments
import { runChecks } from './shared/checks.js';
// no other page imports the page of a deployment, it registers itself (`views.navigate`)
import './deployment.js';
import { showDeployments } from './deployments.js';
import { showProviders } from './providers.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { showVersion } from './shared/update.js';
import { loadTexts, text } from './shared/views.js';

const { invoke } = window.__TAURI__.core;

// the window closed while a SAK instance is changed: rust keeps it open and asks here first (DA-29)
window.__TAURI__.event?.listen('close-requested', async () => {
  const quit = await confirmAction({
    title: text('ui.close.title'),
    message: text('ui.close.text'),
    action: text('ui.close.quit'),
    cancel: text('ui.close.stay'),
    danger: true,
  });
  if (quit) await invoke('quit_working');
});

await loadTexts();
await showVersion();
const next = await runChecks();
if (next === 'providers') {
  await showProviders();
} else {
  await showDeployments();
}
