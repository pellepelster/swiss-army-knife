// runs the checks of src-tauri/src/checks.rs and shows the one running, a failing check
// ends the app with a dialog of its own; the texts come translated from rust
const { invoke, Channel } = window.__TAURI__.core;

const status = document.getElementById('check-status');
const onEvent = new Channel();
onEvent.onmessage = (message) => {
  status.textContent = message.text;
};

invoke('locale').then((locale) => {
  document.documentElement.lang = locale;
});

invoke('run_checks', { onEvent }).catch((error) => {
  status.textContent = String(error);
  status.classList.add('check-status-failed');
});
