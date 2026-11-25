// the repositories of restic the instance keeps (BACKUP-1): their list by description, a card each with
// a ⋮ menu, and the form that creates one by its description; the instance derives its name for the
// command line. Their tokens never come to the page (BACKUP-2)
import {
  button,
  copyEntry,
  createButton,
  disable,
  entry,
  loadFailed,
  message,
  row,
  rowDetail,
  rowName,
  saveFailed,
  signedOut,
} from './rows.js';
import { runFailure, runningPlan, size } from './run.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { $, invoke, setBusy, setFailure, setStatus, text } from './shared/views.js';

const list = $('repositories-list');
const status = $('repositories-status');
const total = $('repositories-total');
const form = $('repository-form');
const description = $('repository-description');
const nameOfDescription = $('repository-name');
const descriptionError = $('repository-description-error');
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
  total.textContent = '';
  setStatus(status, text('ui.repositories.loading'));
  try {
    const repositories = await invoke('repositories');
    list.replaceChildren(...repositories.map(repositoryRow));
    // what they take on the instance together, as the web app shows it
    if (repositories.length > 0) {
      total.textContent = text('ui.repositories.total', { size: size(repositories.reduce((sum, each) => sum + each.size, 0)) });
    }
    setStatus(status, repositories.length === 0 ? text('ui.repositories.empty') : '');
  } catch (error) {
    loadFailed(error, status, shell);
  }
}

function repositoryRow(repository) {
  const copy = copyEntry(
    text('ui.repositories.copy_url'),
    repository.url,
    status,
    text('ui.repositories.copied', { name: repository.description }),
  );
  // a password kept in the keychain (BACKUP-3) is forgotten here; disabled while none is kept
  const forget = entry(text('ui.repositories.forget_password'), async () => {
    try {
      await invoke('forget_password', { repositoryId: repository.id });
      disableForget(forget);
      setStatus(status, text('ui.repositories.forgot', { name: repository.description }));
    } catch (failure) {
      const said = runFailure(failure, shell, repository.description, (error) => error);
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
  // a soft delete with its backup plans (BACKUP-9); not while a backup or restore runs, which may
  // use it (UX-8)
  const remove = entry(text('ui.delete'), () => deleteRepository(repository), { danger: true });
  if (runningPlan() !== null) disable(remove, text('ui.repositories.delete_running'));
  // what its files take on the instance, encrypted as restic wrote them: big, at the right, as in the
  // web app
  const stored = document.createElement('span');
  stored.className = 'row-size sak-mono';
  stored.textContent = size(repository.size);
  stored.title = text('ui.repositories.size', { size: size(repository.size) });
  stored.dataset.testid = 'size';
  // a repository has nothing to open but its address, the menu copies it
  return row(repository.description, [rowName(repository.description), rowDetail(repository.url)], null, [copy, forget, remove], [stored]);
}

async function deleteRepository(repository) {
  const confirmed = await confirmAction({
    title: text('ui.repositories.delete_title', { name: repository.description }),
    message: text('ui.repositories.delete_confirm', { name: repository.description }),
    action: text('ui.delete'),
    danger: true,
  });
  if (!confirmed) return;
  try {
    await invoke('delete_repository', { id: repository.id });
    // its password is of no use any more (BACKUP-3)
    await invoke('forget_password', { repositoryId: repository.id }).catch(() => {});
    await showRepositories();
  } catch (error) {
    if (!signedOut(error, shell)) setFailure(status, text('ui.repositories.delete_failed', { error: message(error) }));
  }
}

function disableForget(forget) {
  forget.disabled = true;
  forget.title = text('ui.repositories.none_kept');
}

function showCreate() {
  shell.title(text('ui.repositories.create'));
  shell.toolbar();
  shell.page('page-repository');
  description.value = '';
  showName();
  descriptionError.textContent = '';
  setStatus(formStatus, '');
  submit.disabled = false;
  description.focus();
}

// the name the instance derives from a description (ResticRepositoryService.nameOf): lower case,
// umlauts written out, other accents gone, everything but a-z, 0-9, - and _ a hyphen, no hyphen
// twice, none at the start or the end, at most 63 characters; empty when nothing is left
export function nameOf(text) {
  const written = text.toLowerCase().replaceAll('ä', 'ae').replaceAll('ö', 'oe').replaceAll('ü', 'ue').replaceAll('ß', 'ss');
  const plain = written.normalize('NFD').replace(/\p{M}+/gu, '');
  const trim = (value) => value.replace(/^[-_]+|[-_]+$/g, '');
  return trim(trim(plain.replace(/[^a-z0-9_-]+/g, '-').replace(/-{2,}/g, '-')).slice(0, 63));
}

function showName() {
  const name = nameOf(description.value);
  nameOfDescription.textContent = name ? text('ui.repositories.name_preview', { name }) : '';
}

function checkDescription() {
  const said = description.value.trim() === '' || nameOf(description.value) === '' ? text('ui.repositories.no_name') : '';
  descriptionError.textContent = said;
  submit.disabled = said !== '';
  return said === '';
}

description.addEventListener('input', () => {
  showName();
  descriptionError.textContent = '';
  submit.disabled = false;
});
description.addEventListener('blur', checkDescription);

// the errors of the api by the field they belong to (UX-15), the others above the buttons
const FIELD_OF = {
  'restic.invalid_description': 'description',
  'restic.no_name': 'description',
  'restic.repository_exists': 'description',
};

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!checkDescription()) return;
  setBusy(form, true);
  setStatus(formStatus, text('ui.saving'));
  try {
    await invoke('create_repository', { description: description.value.trim() });
    await showRepositories();
  } catch (error) {
    if (signedOut(error, shell)) return;
    setStatus(formStatus, '');
    saveFailed(error, FIELD_OF, () => descriptionError, formStatus);
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
