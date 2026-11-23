// the question before an action that can not be taken back or that changes much, e.g. a destroy, a
// reboot, a restore where the files were: it answers true for the action, false when it is
// cancelled. The same in each desktop app; the dialog builds itself into the body on its first use
// (the look is `.dialog` of views.css)
import { toggleOf } from './menu.js';
import { element, text } from './views.js';

let built = null;
let answer = null;
// the element that had the focus when the dialog opened
let opener = null;

function build() {
  const title = element('h2', { class: 'h5 mb-0', id: 'confirm-dialog-title' });
  const message = element('p', { id: 'confirm-dialog-message' });
  const cancel = element('button', { type: 'button', class: 'btn btn-outline-secondary', id: 'confirm-dialog-cancel' });
  const submit = element('button', { type: 'submit', class: 'btn', id: 'confirm-dialog-submit' });
  const form = element('form', { method: 'dialog', novalidate: '' }, [
    title,
    message,
    // the buttons of a form, bottom right, the main one rightmost (UX-9)
    element('div', { class: 'form-buttons' }, [element('div', { class: 'btn-group', role: 'group' }, [cancel, submit])]),
  ]);
  const dialog = element(
    'dialog',
    { id: 'confirm-dialog', 'aria-labelledby': 'confirm-dialog-title', 'aria-describedby': 'confirm-dialog-message' },
    [form],
  );
  document.body.append(dialog);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    close(true);
  });
  cancel.addEventListener('click', () => close(false));
  // Escape cancels too
  dialog.addEventListener('cancel', (event) => {
    event.preventDefault();
    close(false);
  });
  return { dialog, title, message, cancel, submit };
}

function close(value) {
  const resolve = answer;
  answer = null;
  built.dialog.close();
  // the focus goes back to the element that had it. An entry of a menu is hidden by now: the toggle
  // of its menu takes the focus
  (toggleOf(opener) ?? opener)?.focus();
  opener = null;
  resolve?.(value);
}

// `title`, `message` and `action` (the label of the button that does it) are texts; `danger` for an
// action that deletes or destroys: its button is red (UX-3); `cancel` is the label of the safe
// answer, the `ui.cancel` each app has; `className` an own class of the app for the dialog
export function confirmAction({ title, message, action, danger = false, cancel = text('ui.cancel'), className = '' }) {
  built ??= build();
  // a question that is still open is cancelled
  if (answer) close(false);
  built.dialog.className = className ? `dialog ${className}` : 'dialog';
  built.title.textContent = title;
  built.message.textContent = message;
  built.cancel.textContent = cancel;
  built.submit.textContent = action;
  built.submit.classList.toggle('btn-danger', danger);
  built.submit.classList.toggle('btn-primary', !danger);
  opener = document.activeElement;
  built.dialog.showModal();
  // the safe answer has the focus
  built.cancel.focus();
  return new Promise((resolve) => {
    answer = resolve;
  });
}
