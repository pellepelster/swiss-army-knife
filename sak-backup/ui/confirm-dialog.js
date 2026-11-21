// the question before an action that changes files of this computer, e.g. a restore where the files
// were: it answers true for the action, false when it is cancelled
const $ = (id) => document.getElementById(id);
const dialog = $('confirm-dialog');

let answer = null;

function close(value) {
  answer?.(value);
  answer = null;
  dialog.close();
}

$('confirm-dialog-form').addEventListener('submit', (event) => {
  event.preventDefault();
  close(true);
});
$('confirm-dialog-cancel').addEventListener('click', () => close(false));
// Escape cancels too
dialog.addEventListener('cancel', (event) => {
  event.preventDefault();
  close(false);
});

export function confirmAction({ title, message, action }) {
  $('confirm-dialog-title').textContent = title;
  $('confirm-dialog-message').textContent = message;
  $('confirm-dialog-submit').textContent = action;
  dialog.showModal();
  // the safe answer has the focus
  $('confirm-dialog-cancel').focus();
  return new Promise((resolve) => {
    answer = resolve;
  });
}
