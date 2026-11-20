// the repositories of restic (BACKUP-1 to BACKUP-6): their list, one card each (UX-11) with a ⋮
// menu (UX-12), and the form that creates one or changes one of the user. The secrets of a
// connection never come to the page: a secret the user does not type again is sent as kept
import { setFailure, setStatus, text } from './shared/views.js';

const { invoke } = window.__TAURI__.core;

const NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const RATE = /^[1-9][0-9]{0,6}(KiB|MiB|GiB)\/s$/;
// the variables and options the form has fields for; the others of a repository are kept as they are
const FORM_ENV = ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_DEFAULT_REGION'];

const $ = (id) => document.getElementById(id);
const list = $('repositories-list');
const listStatus = $('repositories-status');
const form = $('repository-form');
const fields = {
  name: $('repository-name'),
  url: $('repository-url'),
  accessKey: $('repository-access-key'),
  secretKey: $('repository-secret-key'),
  region: $('repository-region'),
  upload: $('repository-upload'),
  download: $('repository-download'),
  shared: $('repository-shared'),
};
const kinds = { s3: $('repository-kind-s3'), local: $('repository-kind-local') };
const formStatus = $('repository-status');
const submit = $('repository-submit');

// what the page needs of the shell: its header, its toolbar, a page to show and what to do when the
// session is gone
let shell = null;
// the repository the form changes, null while it creates one
let editing = null;
// the fields that were left with an error (UX-6)
const invalid = new Set();

export function initRepositories(pageShell) {
  shell = pageShell;
}

// the list of the repositories, the page the navigation opens
export async function showRepositories() {
  shell.title(text('ui.nav.repositories'));
  const create = button('btn btn-primary', text('ui.repositories.create'), () => showForm(null));
  create.prepend(icon(), ' ');
  shell.toolbar(create);
  shell.page('page-repositories');
  list.replaceChildren();
  setStatus(listStatus, text('ui.repositories.loading'));
  try {
    const repositories = await invoke('repositories');
    list.replaceChildren(...repositories.map(row));
    setStatus(listStatus, repositories.length === 0 ? text('ui.repositories.empty') : '');
  } catch (error) {
    if (!signedOut(error)) setFailure(listStatus, text('ui.repositories.load_failed', { error: message(error) }));
  }
}

function icon() {
  const plus = document.createElement('i');
  plus.className = 'sak-icon-plus';
  plus.setAttribute('aria-hidden', 'true');
  return plus;
}

function button(className, label, onClick) {
  const created = document.createElement('button');
  created.type = 'button';
  created.className = className;
  created.textContent = label;
  created.addEventListener('click', onClick);
  return created;
}

// one repository: the row opens its form, the ⋮ menu has Edit and Delete
function row(repository) {
  const item = document.createElement('li');
  item.className = 'card repository';
  const open = button('repository-open', '', () => showForm(repository));
  const name = document.createElement('span');
  name.className = 'repository-name';
  name.textContent = repository.name;
  const url = document.createElement('span');
  url.className = 'repository-url sak-mono';
  url.textContent = repository.url;
  const badges = document.createElement('span');
  badges.className = 'repository-badges';
  if (repository.shared) badges.append(badge(text('ui.repositories.shared_badge')));
  if (!repository.mine) badges.append(badge(text('ui.repositories.others_badge')));
  open.append(name, badges, url);
  item.append(open, menu(repository));
  return item;
}

function badge(label) {
  const created = document.createElement('span');
  created.className = 'badge text-bg-secondary';
  created.textContent = label;
  return created;
}

function menu(repository) {
  const wrapper = document.createElement('div');
  wrapper.className = 'row-menu';
  const toggle = button('btn btn-outline-secondary btn-sm', '⋮', (event) => {
    event.stopPropagation();
    const open = !entries.classList.contains('show');
    closeMenus();
    entries.classList.toggle('show', open);
    toggle.setAttribute('aria-expanded', String(open));
  });
  toggle.setAttribute('aria-label', text('ui.repositories.actions', { name: repository.name }));
  toggle.setAttribute('aria-haspopup', 'menu');
  toggle.setAttribute('aria-expanded', 'false');
  const entries = document.createElement('ul');
  entries.className = 'dropdown-menu dropdown-menu-end';
  entries.setAttribute('role', 'menu');
  const edit = button('dropdown-item', text('ui.repositories.open'), () => showForm(repository));
  edit.setAttribute('role', 'menuitem');
  if (!repository.mine) disable(edit, text('ui.repositories.only_owner'));
  // there is no delete yet: disabled, never hidden (UX-8, UX-12)
  const remove = button('dropdown-item text-danger', text('ui.repositories.delete'), () => {});
  remove.setAttribute('role', 'menuitem');
  disable(remove, text('ui.repositories.delete_unavailable'));
  entries.append(...[edit, remove].map((entry) => {
    const line = document.createElement('li');
    line.append(entry);
    return line;
  }));
  wrapper.append(toggle, entries);
  return wrapper;
}

function disable(element, reason) {
  element.disabled = true;
  element.title = reason;
}

function closeMenus() {
  for (const open of list.querySelectorAll('.dropdown-menu.show')) {
    open.classList.remove('show');
    open.previousElementSibling?.setAttribute('aria-expanded', 'false');
  }
}

document.addEventListener('click', closeMenus);
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeMenus();
});

function kind() {
  return kinds.local.checked ? 'local' : 's3';
}

// the form of a repository: empty to create one, filled to change one; read only when it is a shared
// one of another user
function showForm(repository) {
  closeMenus();
  editing = repository;
  invalid.clear();
  const env = repository?.env ?? {};
  fields.name.value = repository?.name ?? '';
  const local = repository ? !repository.url.startsWith('s3:') : false;
  kinds.local.checked = local;
  kinds.s3.checked = !local;
  fields.url.value = repository?.url ?? '';
  fields.accessKey.value = env.AWS_ACCESS_KEY_ID?.value ?? '';
  fields.secretKey.value = '';
  fields.secretKey.placeholder = env.AWS_SECRET_ACCESS_KEY?.secret ? text('ui.repositories.secret_kept') : '';
  fields.region.value = env.AWS_DEFAULT_REGION?.value ?? '';
  fields.upload.value = repository?.limits?.upload ?? '';
  fields.download.value = repository?.limits?.download ?? '';
  fields.shared.checked = repository?.shared ?? false;
  for (const error of form.querySelectorAll('.field-error')) error.textContent = '';
  setStatus(formStatus, '');

  const readOnly = repository !== null && !repository.mine;
  for (const element of form.querySelectorAll('input')) element.disabled = readOnly;
  submit.textContent = text(repository ? 'ui.repositories.save' : 'ui.repositories.create');
  if (readOnly) setStatus(formStatus, text('ui.repositories.only_owner'));
  shell.title(text(repository ? 'ui.repositories.edit' : 'ui.repositories.create'));
  shell.toolbar();
  shell.page('page-repository');
  showKind();
  update();
  if (!readOnly) fields.name.focus();
}

// the fields of the kind: the s3 ones only for a bucket, sharing never for a local folder (BACKUP-6)
function showKind() {
  const local = kind() === 'local';
  $('repository-s3').hidden = local;
  $('repository-url-label').textContent = text(local ? 'ui.repositories.path' : 'ui.repositories.bucket');
  $('repository-url-hint').textContent = text(local ? 'ui.repositories.path_hint' : 'ui.repositories.bucket_hint');
  const readOnly = editing !== null && !editing.mine;
  if (local) fields.shared.checked = false;
  fields.shared.disabled = local || readOnly;
  $('repository-shared-hint').textContent = text(local ? 'ui.repositories.shared_local' : 'ui.repositories.shared_hint');
}

for (const radio of Object.values(kinds)) {
  radio.addEventListener('change', () => {
    showKind();
    if (fields.url.value.trim()) check('url');
    update();
  });
}

// the error of a field, empty when it is valid
function errorOf(field) {
  const value = fields[field].value.trim();
  switch (field) {
    case 'name':
      return NAME.test(value) ? '' : text('ui.repositories.invalid_name');
    case 'url':
      if (kind() === 'local') return value.startsWith('/') ? '' : text('ui.repositories.invalid_path');
      return /^s3:(https:\/\/)?[^/@\s]+\/[^\s?#]+/.test(value) && !value.startsWith('s3:http://')
        ? ''
        : text('ui.repositories.invalid_bucket');
    case 'upload':
    case 'download':
      return value === '' || RATE.test(value) ? '' : text('ui.repositories.invalid_limit');
    default:
      return '';
  }
}

const CHECKED = ['name', 'url', 'upload', 'download'];

// a field is checked when it is left (UX-6)
function check(field) {
  const error = errorOf(field);
  $(`repository-${field}-error`).textContent = error;
  if (error) invalid.add(field);
  else invalid.delete(field);
  update();
}

for (const field of CHECKED) {
  fields[field].addEventListener('blur', () => check(field));
  // typing clears the error until the field is left again
  fields[field].addEventListener('input', () => {
    $(`repository-${field}-error`).textContent = '';
    invalid.delete(field);
    update();
  });
}

// the save is disabled while a field is invalid or the repository is of another user
function update() {
  submit.disabled = invalid.size > 0 || (editing !== null && !editing.mine);
}

// what the api takes: the fields of the form, the other variables and options of the repository as
// they are; a secret not typed again is kept
function request() {
  const s3 = kind() === 's3';
  const before = editing?.env ?? {};
  const env = {};
  if (s3) {
    const value = (name, given) => {
      if (given) env[name] = { value: given };
    };
    value('AWS_ACCESS_KEY_ID', fields.accessKey.value.trim());
    if (fields.secretKey.value) {
      env.AWS_SECRET_ACCESS_KEY = { secret: fields.secretKey.value };
    } else if (before.AWS_SECRET_ACCESS_KEY?.secret) {
      env.AWS_SECRET_ACCESS_KEY = { kept: true };
    }
    value('AWS_DEFAULT_REGION', fields.region.value.trim());
    for (const [name, entry] of Object.entries(before)) {
      if (FORM_ENV.includes(name)) continue;
      env[name] = entry.secret ? { kept: true } : { value: entry.value };
    }
  }
  const limit = (field) => fields[field].value.trim() || null;
  return {
    name: fields.name.value.trim(),
    shared: fields.shared.checked,
    url: fields.url.value.trim(),
    env,
    options: s3 && editing?.url.startsWith('s3:') ? editing.options : {},
    limits: { upload: limit('upload'), download: limit('download') },
  };
}

// the field each error of the api belongs to (UX-15); the others go above the buttons
const FIELD_OF = {
  'restic.invalid_name': 'name',
  'restic.repository_exists': 'name',
  'restic.invalid_url': 'url',
  'restic.login_in_url': 'url',
  'restic.local_not_shared': 'shared',
};

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  for (const field of CHECKED) check(field);
  if (invalid.size > 0) return;
  submit.disabled = true;
  setStatus(formStatus, text('ui.repositories.saving'));
  try {
    const repository = request();
    if (editing) {
      await invoke('update_repository', { id: editing.id, repository });
    } else {
      await invoke('create_repository', { repository });
    }
    await showRepositories();
  } catch (error) {
    if (signedOut(error)) return;
    setStatus(formStatus, '');
    const rest = [];
    for (const { code, message: said } of error?.errors ?? []) {
      const field = FIELD_OF[code];
      if (field) {
        $(`repository-${field}-error`).textContent = said;
        if (field !== 'shared') invalid.add(field);
      } else {
        rest.push(said);
      }
    }
    if (rest.length > 0 || !error?.errors?.length) {
      setFailure(formStatus, text('ui.repositories.save_failed', { error: rest.join(' ') || message(error) }));
    }
    update();
  }
});

$('repository-cancel').addEventListener('click', () => showRepositories());

// the session is gone: the shell shows the sign-in
function signedOut(error) {
  if (error?.kind !== 'signed_out') return false;
  shell.signedOut();
  return true;
}

function message(error) {
  if (error?.kind === 'refused') return error.errors.map((each) => each.message).join(' ') || String(error.status);
  return error?.message ?? String(error);
}
