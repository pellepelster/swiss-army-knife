// runs the checks of src-tauri/src/checks.rs and shows the one running, a failing check
// ends the app with a dialog of its own
const { invoke, Channel } = window.__TAURI__.core;

const status = document.getElementById('check-status');
const onEvent = new Channel();
onEvent.onmessage = (message) => {
  switch (message.event) {
    case 'started':
      status.textContent = `Checking the ${message.name}…`;
      break;
    case 'passed':
      status.textContent = message.message;
      break;
    case 'finished':
      status.textContent = 'Ready';
      break;
  }
};

invoke('run_checks', { onEvent }).catch((error) => {
  status.textContent = String(error);
  status.classList.add('check-status-failed');
});
