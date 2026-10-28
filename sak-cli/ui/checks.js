// the checks of src-tauri/src/checks/ as a list, each with its state, a progress bar while it runs
// and what it found or why it failed below its name; the promise answers what follows the splash
// screen. A failing check stays on the splash screen with its reason, quit is all that is left
const { invoke, Channel } = window.__TAURI__.core;

// the checkmarks stay a moment before the next view
const SHOWN_DONE_MS = 600;

const list = document.getElementById('checks');
const errorText = document.getElementById('checks-error');
const quitButton = document.getElementById('splash-quit');
const rows = new Map();
// a check failed: its row says why, the app can only quit
let failed = false;

quitButton.addEventListener('click', () => invoke('quit', { failed }));

// the end of the checks: the reason inline (unless the row of the failed check has it), quit the
// one action left
function fail(error) {
  failed = true;
  if (![...rows.values()].some((item) => item.dataset.state === 'failed')) {
    errorText.textContent = String(error);
    errorText.hidden = false;
  }
  quitButton.classList.replace('btn-outline-secondary', 'btn-primary');
  quitButton.focus();
}

// the led of the theme for the state of a check
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
  return item;
}

// never settles after a failure: nothing follows the splash screen then
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
          // without a value a progress bar only moves, until the check reports how far it is
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
