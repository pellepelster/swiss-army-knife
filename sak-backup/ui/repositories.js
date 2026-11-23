// the repositories of restic the instance keeps (BACKUP-1): their list, a card each with a ⋮ menu,
// and the form that creates one by its name. Their tokens never come to the page (BACKUP-2)
import {
  badge,
  button,
  copyEntry,
  createButton,
  entry,
  loadFailed,
  nameField,
  row,
  rowDetail,
  rowName,
  saveFailed,
  signedOut,
  unavailableDelete,
} from './rows.js';
import { runFailure } from './run.js';
import { $, invoke, setBusy, setFailure, setStatus, text } from './shared/views.js';

const list = $('repositories-list');
const status = $('repositories-status');
const form = $('repository-form');
const name = $('repository-name');
const nameError = $('repository-name-error');
const formStatus = $('repository-status');
const submit = $('repository-submit');

let shell = null;

export function initRepositories(pageShell) {
  shell = pageShell;
}

export async function showRepositories() {
  shell.title(text('ui.nav.repositories'));
  shell.toolbar(createButton(text('ui.repositories.create'), showCreate));
  shell.page('page-repositories');
  list.replaceChildren();
  setStatus(status, text('ui.repositories.loading'));
  try {
    const repositories = await invoke('repositories');
    list.replaceChildren(...repositories.map(repositoryRow));
    setStatus(status, repositories.length === 0 ? text('ui.repositories.empty') : '');
  } catch (error) {
    loadFailed(error, status, shell);
  }
}

function repositoryRow(repository) {
  // the date in the locale of the user (UX-5)
  const created = badge(text('ui.repositories.created', { date: new Date(repository.createdAt).toLocaleDateString() }));
  const copy = copyEntry(
    text('ui.repositories.copy_url'),
    repository.url,
    status,
    text('ui.repositories.copied', { name: repository.name }),
  );
  // a password kept in the keychain (BACKUP-3) is forgotten here; disabled while none is kept
  const forget = entry(text('ui.repositories.forget_password'), async () => {
    try {
      await invoke('forget_password', { repositoryId: repository.id });
      disableForget(forget);
      setStatus(status, text('ui.repositories.forgot', { name: repository.name }));
    } catch (failure) {
      const said = runFailure(failure, shell, repository.name, (error) => error);
      if (said !== null) setFailure(status, said);
    }
  });
  disableForget(forget);
  invoke('password_kept', { repositoryId: repository.id })
    .then((kept) => {
      if (kept) {
        forget.disabled = false;
        forget.title = '';
      }
    })
    .catch(() => {});
  const remove = unavailableDelete(text('ui.repositories.delete_unavailable'));
  // a repository has nothing to open but its address, the menu copies it
  return row(repository.name, [rowName(repository.name), created, rowDetail(repository.url)], null, [copy, forget, remove]);
}

function disableForget(forget) {
  forget.disabled = true;
  forget.title = text('ui.repositories.none_kept');
}

function showCreate() {
  shell.title(text('ui.repositories.create'));
  shell.toolbar();
  shell.page('page-repository');
  name.value = '';
  nameError.textContent = '';
  setStatus(formStatus, '');
  submit.disabled = false;
  name.focus();
}

const checkName = nameField(name, nameError, submit);

// the errors of the api by the field they belong to (UX-15), the others above the buttons
const FIELD_OF = {
  'restic.invalid_name': 'name',
  'restic.repository_exists': 'name',
};

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!checkName()) return;
  setBusy(form, true);
  setStatus(formStatus, text('ui.saving'));
  try {
    await invoke('create_repository', { name: name.value.trim() });
    await showRepositories();
  } catch (error) {
    if (signedOut(error, shell)) return;
    setStatus(formStatus, '');
    saveFailed(error, FIELD_OF, () => nameError, formStatus);
  } finally {
    setBusy(form, false);
  }
});

$('repository-cancel').addEventListener('click', () => showRepositories());

// for plans.js: a button that leads to the creation of a repository
export function createRepositoryButton(pageShell) {
  return button('btn btn-link p-0', text('ui.repositories.create'), () => {
    pageShell.open('repositories');
    showCreate();
  });
}
