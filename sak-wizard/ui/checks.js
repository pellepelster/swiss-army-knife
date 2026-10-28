// runs the checks of src-tauri/src/checks.rs and shows the one running; a failing check ends
// the app with a dialog of its own, the promise answers what follows the splash screen
const { invoke, Channel } = window.__TAURI__.core;

export function runChecks() {
  const status = document.getElementById('check-status');
  return new Promise((resolve, reject) => {
    const onEvent = new Channel();
    onEvent.onmessage = (message) => {
      status.textContent = message.text;
      if (message.event === 'finished') {
        resolve(message.next);
      }
    };
    invoke('run_checks', { onEvent }).catch((error) => {
      status.textContent = String(error);
      status.classList.add('status-failed');
      reject(error);
    });
  });
}
