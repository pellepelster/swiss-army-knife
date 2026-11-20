// the sign-in of a desktop app, the same in each (the commands of sak-desktop-shared/src/window.rs):
// the icon, the title and the intro of the app above the SAK address and the button that signs in
// through the browser. The view builds itself into the body on its first show
import { setFailure, setStatus, show, text } from './views.js';

const { invoke } = window.__TAURI__.core;

let view = null;
let options = null;

function element(tag, attributes = {}, children = []) {
  const created = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) created.setAttribute(name, value);
  created.append(...children);
  return created;
}

function build() {
  const address = element('input', {
    class: 'form-control',
    id: 'login-address',
    type: 'text',
    autocomplete: 'url',
    spellcheck: 'false',
    autocapitalize: 'off',
    'aria-describedby': 'login-address-hint',
  });
  const status = element('p', { class: 'hint', id: 'login-status', 'aria-live': 'polite' });
  const cancel = element('button', { type: 'button', class: 'btn btn-outline-secondary', id: 'login-cancel' }, [text('ui.login.cancel')]);
  const submit = element('button', { type: 'submit', class: 'btn btn-primary', id: 'login-submit' }, [text('ui.login.sign_in')]);
  const form = element('form', { class: 'login-form', novalidate: '' }, [
    element('div', {}, [
      element('label', { class: 'form-label', for: 'login-address' }, [text('ui.login.address')]),
      address,
      element('div', { class: 'form-text', id: 'login-address-hint' }, [text('ui.login.address_hint')]),
    ]),
    status,
    // the buttons of a form, bottom right (UX-9)
    element('div', { class: 'login-buttons' }, [cancel, submit]),
  ]);
  const title = element('h1', { class: 'login-title' });
  const intro = element('p', { class: 'hint' });
  const main = element('main', { class: 'view login', id: 'view-login', hidden: '' }, [
    element('div', { class: 'login-brand' }, [
      element('img', { class: 'login-icon', src: 'theme/images/icon.svg', alt: '' }),
      title,
      intro,
      form,
    ]),
  ]);
  document.body.append(main);

  const waiting = (yes) => {
    submit.disabled = yes;
    address.disabled = yes;
    cancel.hidden = !yes;
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    waiting(true);
    setStatus(status, text('ui.login.waiting'));
    try {
      const user = await invoke('login', { address: address.value });
      options.onSignedIn(user);
    } catch (error) {
      waiting(false);
      setFailure(status, String(error));
      address.focus();
    }
  });
  cancel.addEventListener('click', () => invoke('cancel_login'));
  return { main, title, intro, address, status, waiting };
}

// shows the sign-in: `title` and `intro` are the keys of texts of the app, `onSignedIn` gets the
// user (who-am-i) once the browser came back. The address is the one signed in at last
export async function showLogin({ title, intro, onSignedIn }) {
  options = { onSignedIn };
  view ??= build();
  view.title.textContent = text(title);
  view.intro.textContent = text(intro);
  view.waiting(false);
  setStatus(view.status, '');
  const [last] = await invoke('known_instances');
  if (last && !view.address.value) view.address.value = last;
  show('view-login');
  view.address.focus();
}

// who is signed in, null without a session
export function signedInUser() {
  return invoke('user');
}

// forgets the session
export function signOut() {
  return invoke('logout');
}

// the name the apps show for the user: the name an admin gave, else the address; the admin of the
// password is `adminText`
export function displayName(user, adminText) {
  if (user.name) return user.name;
  return user.username === 'admin' ? adminText : user.username;
}
