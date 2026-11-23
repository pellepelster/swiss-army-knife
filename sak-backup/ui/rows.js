// what the pages share: a card for each row (UX-11) with a ⋮ menu at its right end (UX-12) and its
// common entries, a button, the field of a name, the choice of a repository, the host name of this
// computer, and the errors of a call of the api
import { menu } from './shared/menu.js';
import { setFailure, setStatus, text } from './shared/views.js';

const { invoke } = window.__TAURI__.core;

// a name of a repository or a backup plan
const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

export function button(className, label, onClick) {
  const created = document.createElement('button');
  created.type = 'button';
  created.className = className;
  created.textContent = label;
  created.addEventListener('click', onClick);
  return created;
}

// the plus of the main create action of a page (UX-2)
export function createButton(label, onClick) {
  const created = button('btn btn-primary', label, onClick);
  const plus = document.createElement('i');
  plus.className = 'sak-icon-plus';
  plus.setAttribute('aria-hidden', 'true');
  created.prepend(plus, ' ');
  return created;
}

export function disable(element, reason) {
  element.disabled = true;
  element.title = reason;
}

export function badge(label) {
  const created = document.createElement('span');
  // outlined and muted, as the badges of the web app
  created.className = 'badge border text-body-secondary fw-normal';
  created.textContent = label;
  return created;
}

// the name of a row, and a detail below it, e.g. paths or an address
export function rowName(name) {
  const created = document.createElement('span');
  created.className = 'row-name';
  created.textContent = name;
  return created;
}

export function rowDetail(detail, title = detail) {
  const created = document.createElement('span');
  created.className = 'row-detail sak-mono';
  created.textContent = detail;
  created.title = title;
  return created;
}

// a row: its content, opened by onOpen when it has one, the buttons of its actions and its menu of
// entries
export function row(name, content, onOpen, entries, actions = []) {
  const item = document.createElement('li');
  item.className = 'card row-card';
  const body = document.createElement(onOpen ? 'button' : 'div');
  body.className = 'row-body';
  if (onOpen) {
    body.type = 'button';
    body.addEventListener('click', onOpen);
  }
  body.append(...content);
  const buttons = document.createElement('div');
  buttons.className = 'row-actions';
  buttons.append(...actions);
  item.append(body, buttons, rowMenu(name, entries));
  return item;
}

// an entry of a row menu: its label, what it does, the reason it is disabled and whether it deletes
export function entry(label, onClick, { disabled = null, danger = false } = {}) {
  const created = button(`dropdown-item${danger ? ' text-danger' : ''}`, label, onClick);
  created.setAttribute('role', 'menuitem');
  if (disabled) disable(created, disabled);
  return created;
}

// the entry that copies a value, e.g. an address, and says so in status
export function copyEntry(label, value, status, copied) {
  return entry(label, async () => {
    try {
      await navigator.clipboard.writeText(value);
      setStatus(status, copied);
    } catch (error) {
      setFailure(status, String(error));
    }
  });
}

// the delete of a row that is not possible yet: disabled with the reason, never hidden (UX-8)
export function unavailableDelete(reason) {
  return entry(text('ui.delete'), () => {}, { disabled: reason, danger: true });
}

// the ⋮ menu of a row (shared/menu.js), below its button at the right end of the row
function rowMenu(name, entries) {
  const wrapper = document.createElement('div');
  wrapper.className = 'row-menu';
  const list = document.createElement('ul');
  list.className = 'dropdown-menu menu-end';
  list.setAttribute('role', 'menu');
  const toggle = button('btn btn-outline-secondary btn-sm', '⋮', () => {});
  toggle.setAttribute('aria-label', text('ui.actions', { name }));
  toggle.setAttribute('aria-haspopup', 'menu');
  list.append(...entries.map((each) => {
    const line = document.createElement('li');
    line.append(each);
    return line;
  }));
  wrapper.append(toggle, list);
  menu(toggle, list);
  return wrapper;
}

// the text of an error of a call: the messages of the api, or what failed
export function message(error) {
  if (error?.kind === 'refused') return error.errors.map((each) => each.message).join(' ') || String(error.status);
  return error?.message ?? String(error);
}

// the session is gone: the shell shows the sign-in
export function signedOut(error, shell) {
  if (error?.kind !== 'signed_out') return false;
  shell.signedOut();
  return true;
}

// a page could not load: the sign-in when the session is gone, else the error in status
export function loadFailed(error, status, shell) {
  if (!signedOut(error, shell)) setFailure(status, text('ui.load_failed', { error: message(error) }));
}

// the errors of a save by the field they belong to (UX-15): `fieldOf` maps the code of an error of
// the api to a field, `fieldError` gives the element of its error; the others go to status
export function saveFailed(error, fieldOf, fieldError, status) {
  const rest = [];
  for (const { code, message: said } of error?.errors ?? []) {
    const field = fieldOf[code];
    if (field) fieldError(field).textContent = said;
    else rest.push(said);
  }
  if (rest.length > 0 || !error?.errors?.length) {
    setFailure(status, text('ui.save_failed', { error: rest.join(' ') || message(error) }));
  }
}

// the field of a name: checked when it is left (UX-6), typing clears the error until then; the
// check, for the submit
export function nameField(input, error, submit) {
  const check = () => {
    const said = NAME.test(input.value.trim()) ? '' : text('ui.invalid_name');
    error.textContent = said;
    submit.disabled = said !== '';
    return said === '';
  };
  input.addEventListener('blur', check);
  input.addEventListener('input', () => {
    error.textContent = '';
    submit.disabled = false;
  });
  return check;
}

// the repositories as the options of a select, the chosen one kept when it is still there
export function repositoryOptions(select, repositories, chosen = select.value) {
  select.replaceChildren(
    ...repositories.map((each) => {
      const option = document.createElement('option');
      option.value = String(each.id);
      option.textContent = each.name;
      return option;
    }),
  );
  if (chosen && repositories.some((each) => String(each.id) === String(chosen))) select.value = String(chosen);
}

// the host name of this computer, asked once: a backup plan of another one is shown, not changed
let host = null;
export async function thisHost() {
  host ??= await invoke('host');
  return host;
}
