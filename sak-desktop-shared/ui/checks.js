// the checks at the start of a desktop app (sak-desktop-shared, checks.rs; the app's own in its
// core/checks) on its splash screen: <main class="view splash" id="view-splash"> with #checks,
// #checks-error and #splash-quit, checks.css. The commands run_checks and quit. After a failure the
// splash screen stays, quit is the only action
import { Channel, invoke } from './views.js';

// the checkmarks stay a moment before the next view
const SHOWN_DONE_MS = 600;

const list = document.getElementById('checks');
const said = document.getElementById('checks-said');
const errorText = document.getElementById('checks-error');
const quitButton = document.getElementById('splash-quit');
const rows = new Map();
// quit after a failure ends with exit code 1
let failed = false;

quitButton.addEventListener('click', () => invoke('quit', { failed }));

// the error shows unless the row of a failed check shows it
function fail(error) {
  failed = true;
  if (![...rows.values()].some((item) => item.dataset.state === 'failed')) {
    errorText.textContent = String(error);
    errorText.hidden = false;
  }
  quitButton.classList.replace('btn-outline-secondary', 'btn-primary');
  quitButton.disabled = false;
  quitButton.focus();
}

const LEDS = { pending: 'neutral', running: 'busy', ok: 'ok', failed: 'danger' };

function row(check) {
  const item = document.createElement('li');
  item.className = 'check';
  item.dataset.state = 'pending';
  item.innerHTML = `
    <span class="check-state sak-led" data-sak-state="neutral" aria-hidden="true"></span>
    <span class="check-name"></span>
    <progress class="check-progress" max="1" hidden></progress>
    <span class="check-detail"></span>`;
  item.querySelector('.check-name').textContent = check.name;
  return item;
}

function update(id, state, detail) {
  const item = rows.get(id);
  if (state) {
    item.dataset.state = state;
    item.querySelector('.check-state').dataset.sakState = LEDS[state];
    item.querySelector('.check-progress').hidden = state !== 'running';
  }
  if (detail !== undefined) {
    item.querySelector('.check-detail').textContent = detail;
  }
  // a check is read out once, when it ended
  if ((state === 'ok' || state === 'failed') && said) {
    said.textContent = `${item.querySelector('.check-name').textContent}: ${detail ?? ''}`;
  }
  return item;
}

// gives the view after the splash screen; does not settle after a failure
export function runChecks() {
  return new Promise((resolve) => {
    const onEvent = new Channel();
    onEvent.onmessage = (message) => {
      switch (message.event) {
        case 'listed':
          for (const check of message.checks) {
            rows.set(check.id, row(check));
          }
          list.replaceChildren(...rows.values());
          break;
        case 'started':
          // a progress bar without a value moves until the check reports how far it is
          update(message.id, 'running', '').querySelector('.check-progress').removeAttribute('value');
          break;
        case 'progress':
          update(message.id, null, message.text).querySelector('.check-progress').value = message.fraction;
          break;
        case 'passed':
          update(message.id, 'ok', message.text);
          break;
        case 'failed':
          update(message.id, 'failed', message.text);
          break;
        case 'finished':
          setTimeout(() => resolve(message.next), SHOWN_DONE_MS);
          break;
      }
    };
    invoke('run_checks', { onEvent }).catch(fail);
  });
}
