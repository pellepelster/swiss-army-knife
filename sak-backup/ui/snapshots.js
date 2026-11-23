// the snapshots of a repository (BACKUP-3): restic of this computer lists them with the password of
// SAK_BACKUP_REPO_PASSWORD, the kept one or the one the dialog asks for, the newest first, and
// deletes one with the data no other snapshot needs; a snapshot of a backup of the user shows the
// name of the backup plan
import { passwordFromEnvironment, withPassword } from './password-dialog.js';
import {
  badge,
  copyEntry,
  loadFailed,
  repositoryOptions,
  row,
  rowDetail,
  rowName,
  entry,
  thisHost,
} from './rows.js';
import { runFailure, runningPlan } from './run.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { $, invoke, setFailure, setStatus, text } from './shared/views.js';

const form = $('snapshots-form');
const repository = $('snapshots-repository');
const submit = $('snapshots-submit');
const status = $('snapshots-status');
const list = $('snapshots-list');

let shell = null;
let host = null;
// the repository whose snapshots the list shows, its id and its name
let listed = null;
// the backup plans of the user, to name a snapshot by the plan it is of
let plans = [];

export function initSnapshots(pageShell) {
  shell = pageShell;
}

export async function showSnapshots() {
  shell.title(text('ui.nav.snapshots'));
  shell.toolbar();
  shell.page('page-snapshots');
  list.replaceChildren();
  setStatus(status, '');
  try {
    host = await thisHost();
    const [repositories, loaded] = await Promise.all([invoke('repositories'), invoke('plans')]);
    plans = loaded;
    repositoryOptions(repository, repositories);
    // nothing to list without a repository: disabled, never hidden (UX-8)
    submit.disabled = repositories.length === 0;
    if (repositories.length === 0) setStatus(status, text('ui.snapshots.no_repository'));
    $('snapshots-intro').textContent = text(
      (await passwordFromEnvironment()) ? 'ui.snapshots.intro_environment' : 'ui.snapshots.intro',
    );
  } catch (error) {
    loadFailed(error, status, shell);
  }
}

// the backup plan of the user a snapshot is of: the same computer and the same paths
function planOf(snapshot) {
  const paths = [...snapshot.paths].sort().join('\n');
  return plans.find((each) => each.host === snapshot.hostname && [...each.paths].sort().join('\n') === paths);
}

function snapshotRow(snapshot) {
  // the time in the locale of the user (UX-5)
  const time = new Date(snapshot.time).toLocaleString();
  const id = badge(snapshot.shortId);
  id.classList.add('sak-mono');
  const badges = [id, badge(snapshot.hostname === host ? text('ui.snapshots.this_computer') : snapshot.hostname)];
  const plan = planOf(snapshot);
  if (plan) badges.push(badge(plan.name));
  const detail = rowDetail(snapshot.paths.join(', '), snapshot.paths.join('\n'));
  const copy = copyEntry(text('ui.snapshots.copy_id'), snapshot.id, status, text('ui.snapshots.copied', { id: snapshot.shortId }));
  // not while a backup or a restore runs: one run of restic at a time (DA-33)
  const remove = entry(text('ui.delete'), () => deleteSnapshot(snapshot, time, item), {
    danger: true,
    disabled: runningPlan() === null ? null : text('ui.plans.run_busy'),
  });
  const item = row(snapshot.shortId, [rowName(time), ...badges, detail], null, [copy, remove]);
  return item;
}

// the failure of a call of restic, next to the list (UX-15)
function failed(failure, template) {
  const said = runFailure(failure, shell, listed?.name, (error) => text(template, { error }));
  if (said !== null) setFailure(status, said);
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  listed = { id: Number(repository.value), name: repository.selectedOptions[0]?.textContent ?? '' };
  const name = listed.name;
  submit.disabled = true;
  list.replaceChildren();
  setStatus(status, text('ui.snapshots.loading'));
  try {
    const done = await withPassword({ title: text('ui.snapshots.show'), hint: name }, (password, remember) =>
      invoke('snapshots', { repositoryId: listed.id, password, remember }),
    );
    if (done === null) {
      setStatus(status, '');
    } else if (!done.value.initialized) {
      setStatus(status, text('ui.snapshots.not_initialized', { repository: name }));
    } else if (done.value.snapshots.length === 0) {
      setStatus(status, text('ui.snapshots.empty', { repository: name }));
    } else {
      setStatus(status, text('ui.snapshots.count', { count: done.value.snapshots.length, repository: name }));
      list.replaceChildren(...done.value.snapshots.map(snapshotRow));
    }
  } catch (failure) {
    setStatus(status, '');
    failed(failure, 'ui.load_failed');
  } finally {
    submit.disabled = false;
  }
});

// a snapshot can not be restored once it is deleted: asked first
async function deleteSnapshot(snapshot, time, item) {
  const confirmed = await confirmAction({
    title: text('ui.snapshots.delete_title', { id: snapshot.shortId }),
    message: text('ui.snapshots.delete_confirm', { time, repository: listed.name }),
    action: text('ui.delete'),
    danger: true,
  });
  if (!confirmed) return;
  setStatus(status, text('ui.snapshots.deleting', { id: snapshot.shortId }));
  try {
    const done = await withPassword({ title: text('ui.delete'), hint: listed.name }, (password, remember) =>
      invoke('delete_snapshot', { repositoryId: listed.id, snapshotId: snapshot.id, password, remember }),
    );
    if (done === null) {
      setStatus(status, '');
      return;
    }
    item.remove();
    setStatus(status, text('ui.snapshots.deleted', { id: snapshot.shortId }));
  } catch (failure) {
    setStatus(status, '');
    failed(failure, 'ui.snapshots.delete_failed');
  }
}
