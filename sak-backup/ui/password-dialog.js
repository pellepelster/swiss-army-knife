// the dialog that asks for the password of a repository (BACKUP-3): once, or twice for a new
// repository; with an error when the one before was wrong; with a box to keep it in the keychain of
// this computer, never ticked by itself. It answers { password, remember }, or null when it is
// cancelled; nothing of it stays in the page. `withPassword` asks as long as a call needs it
import { toggleOf } from './shared/menu.js';
import { $, invoke, text } from './shared/views.js';

const dialog = $('password-dialog');
const form = $('password-dialog-form');
const password = $('password-dialog-password');
const repeat = $('password-dialog-repeat');
const repeatField = $('password-dialog-repeat-field');
const error = $('password-dialog-error');
const remember = $('password-dialog-remember');

let answer = null;
// the element that had the focus when the dialog opened
let opener = null;

// the text of a password restic refused, by where it came from
const WRONG = {
  wrong_password: 'ui.plans.run_wrong_password',
  wrong_environment_password: 'ui.plans.run_wrong_environment',
  wrong_kept_password: 'ui.plans.run_wrong_kept',
};

// whether SAK_BACKUP_REPO_PASSWORD gives the password, so the window asks for none
let fromEnvironment = null;
export async function passwordFromEnvironment() {
  fromEnvironment ??= await invoke('env_password_set');
  return fromEnvironment;
}

function close(value) {
  answer?.(value);
  answer = null;
  password.value = '';
  repeat.value = '';
  remember.checked = false;
  dialog.close();
  // the focus goes back to the element that had it. An entry of a menu is hidden by now: the toggle
  // of its menu takes the focus
  (toggleOf(opener) ?? opener)?.focus();
  opener = null;
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (!password.value) {
    error.textContent = text('ui.plans.run_no_password');
    return;
  }
  if (!repeatField.hidden && password.value !== repeat.value) {
    error.textContent = text('ui.plans.run_passwords_differ');
    return;
  }
  close({ password: password.value, remember: remember.checked });
});

$('password-dialog-cancel').addEventListener('click', () => close(null));
// Escape cancels too
dialog.addEventListener('cancel', (event) => {
  event.preventDefault();
  close(null);
});
for (const input of [password, repeat]) {
  input.addEventListener('input', () => {
    error.textContent = '';
  });
}

// asks for the password: `title` and `action` as texts, `hint` above the field, `twice` for a new
// repository, `failure` the error of the password before
export function askPassword({ title, action, hint = '', twice = false, failure = '' }) {
  close(null);
  $('password-dialog-title').textContent = title;
  $('password-dialog-hint').textContent = hint;
  $('password-dialog-hint').hidden = !hint;
  $('password-dialog-submit').textContent = action;
  repeatField.hidden = !twice;
  error.textContent = failure;
  opener = document.activeElement;
  dialog.showModal();
  password.focus();
  return new Promise((resolve) => {
    answer = resolve;
  });
}

// calls `call(password, remember, init)` as long as it needs a password: first with none, so rust
// takes the one of SAK_BACKUP_REPO_PASSWORD or the kept one, then with what the dialog gives, again
// when restic refused it. It answers { value } of the call, or null when the user cancelled; every
// other failure of the call is thrown.
// `title` and `action` are the texts of the dialog, `hint` the line above its field. `create` is for
// a call that makes a new repository when `init` is set (the first backup into it): the password is
// typed twice below `create.hint`; with the password of SAK_BACKUP_REPO_PASSWORD nothing is typed,
// so `create.confirm` asks first (BK-6). Without `create` a repository that is not there is a
// failure as every other
export async function withPassword({ title, action = title, hint = '', create = null }, call) {
  let password = null;
  let remember = false;
  let init = false;
  for (;;) {
    try {
      return { value: await call(password, remember, init) };
    } catch (failure) {
      const kind = failure?.kind;
      let asked;
      if (create && kind === 'needs_init_environment') {
        if (!(await create.confirm())) return null;
        init = true;
        continue;
      } else if (create && kind === 'needs_init') {
        init = true;
        asked = await askPassword({ title, action, hint: create.hint, twice: true });
      } else if (kind === 'no_password' || WRONG[kind]) {
        asked = await askPassword({ title, action, hint, twice: init, failure: WRONG[kind] ? text(WRONG[kind]) : '' });
      } else {
        throw failure;
      }
      if (asked === null) return null;
      ({ password, remember } = asked);
    }
  }
}
