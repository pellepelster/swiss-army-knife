// a run of restic for a backup plan of this computer (BACKUP-3, BACKUP-5), a backup or a restore, on
// a page of its own: the password comes from SAK_BACKUP_REPO_PASSWORD, which rust reads, the
// keychain, or the dialog (twice on the first backup into a repository, again when it was wrong); the
// page shows the progress and ends with what restic did, what it skipped, or why it failed (UX-15).
// One run at a time; it goes on while another page is open, and its row opens its page again
import { withPassword } from './password-dialog.js';
import { message, signedOut } from './rows.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { $, Channel, invoke, setFailure, setStatus, text } from './shared/views.js';

// the text of a failure of restic or of the instance (UX-15): its own for the ones the user can
// act on, else `failed` with what went wrong; null when the session is gone and the sign-in shows.
// `repository` is the name of the repository the call was for
export function runFailure(failure, shell, repository, failed) {
  if (failure?.kind === 'api') return signedOut(failure.error, shell) ? null : failed(message(failure.error));
  const known = {
    no_restic: () => text('ui.plans.run_no_restic'),
    cancelled: () => text('ui.plans.run_cancelled'),
    running: () => text('ui.plans.run_busy'),
    running_elsewhere: () => text('ui.plans.run_elsewhere'),
    no_snapshot: () => text('ui.plans.no_snapshot'),
    unknown_snapshot: () => text('ui.snapshots.unknown'),
    other_computer: () => text('ui.plans.run_other_computer', { host: failure.host }),
    repository_missing: () => text('ui.plans.run_repository_missing', { repository }),
  }[failure?.kind];
  return known ? known() : failed(failure?.message ?? String(failure));
}

const progress = $('run-progress');
const bar = $('run-bar');
const status = $('run-status');
const numbers = $('run-numbers');
// the file restic reads now, apart from the live region: it changes too often to be read out
const file = $('run-file');
const skipped = $('run-skipped');
const close = $('run-close');

let shell = null;
// the backup plan whose run the page shows, the name of its repository and what the run does,
// 'backup' or 'restore'; and whether restic runs for it
let shown = null;
let running = false;
// the whole percent of the backup that runs, null before restic tells it and for a restore
let percent = null;
const listeners = new Set();

export function initRun(pageShell) {
  shell = pageShell;
}

// told when a run starts or ends and when its percent changes, so the rows change their button and
// their look
export function onRunsChanged(listener) {
  listeners.add(listener);
}

// the id of the backup plan restic runs for, null while none runs
export function runningPlan() {
  return running ? shown.plan.id : null;
}

// how far the run is, 0 to 100, null when it does not say (style.css: .row-running)
export function runProgress() {
  return running ? percent : null;
}

// the entry of the backup plans in the navigation shows that a run goes on, on every page
function changed() {
  document.querySelector('#nav [data-page="plans"]')?.toggleAttribute('data-running', running);
  for (const listener of listeners) listener();
}

// bytes in the unit that fits, in the locale of the user
export function size(bytes) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value)} ${units[unit]}`;
}

// a time left, in its largest units
export function duration(seconds) {
  if (seconds < 60) return text('ui.plans.run_seconds', { seconds });
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return text('ui.plans.run_minutes', { minutes });
  return text('ui.plans.run_hours', { hours: Math.floor(minutes / 60), minutes: minutes % 60 });
}

// how far a backup is: the files and bytes, how fast restic reads them, and the time left
function progressText(event) {
  const parts = [text('ui.plans.run_progress', {
    files: event.files_done,
    total: event.total_files,
    done: size(event.bytes_done),
    size: size(event.total_bytes),
  })];
  if (event.bytes_per_second != null) parts.push(text('ui.plans.run_rate', { rate: size(event.bytes_per_second) }));
  if (event.seconds_remaining != null && event.percent < 1) parts.push(text('ui.plans.run_left', { left: duration(event.seconds_remaining) }));
  return parts.join(' · ');
}

// the page of the run, as it is
export function showRun() {
  const title = shown.kind === 'restore' ? 'ui.plans.restore_title' : 'ui.plans.run_title';
  shell.title(text(title, { name: shown.plan.name, repository: shown.repository }));
  shell.toolbar();
  shell.page('page-run');
}

// a backup is cancelled, a restore goes on when the page is left
close.addEventListener('click', () => {
  if (running && shown.kind === 'backup') invoke('cancel_backup');
  else shell.open('plans');
});

// one run of restic; it throws the error it ended with
async function run(password, init, remember) {
  running = true;
  percent = null;
  changed();
  progress.hidden = true;
  file.hidden = true;
  numbers.hidden = true;
  skipped.replaceChildren();
  close.textContent = text('ui.plans.run_cancel');
  setStatus(status, text('ui.plans.run_starting'));
  const onEvent = new Channel();
  onEvent.onmessage = (event) => {
    if (event.kind === 'initializing') {
      setStatus(status, text('ui.plans.run_initializing'));
    } else if (event.kind === 'progress') {
      progress.hidden = false;
      progress.setAttribute('aria-label', text('ui.plans.run_progress_label'));
      const now = Math.round(event.percent * 100);
      bar.style.width = `${now}%`;
      progress.setAttribute('aria-valuenow', String(now));
      // the row of the plan follows each whole percent
      if (now !== percent) {
        percent = now;
        changed();
      }
      // the numbers change each second: in a line that is not read out (DA-24)
      if (!status.textContent || status.textContent === text('ui.plans.run_starting')) setStatus(status, text('ui.plans.run_running'));
      numbers.hidden = false;
      numbers.textContent = progressText(event);
      // the start of a long path goes, its end tells the file (style.css)
      file.hidden = !event.current_file;
      file.textContent = event.current_file ? `\u200e${event.current_file}` : '';
      file.title = event.current_file ?? '';
    } else if (event.kind === 'expiring') {
      progress.hidden = true;
      file.hidden = true;
      numbers.hidden = true;
      setStatus(status, text('ui.plans.run_expiring'));
    } else if (event.kind === 'skipped') {
      const line = document.createElement('li');
      line.textContent = text('ui.plans.run_skipped', { message: event.message });
      skipped.append(line);
    }
  };
  try {
    const summary = await invoke('run_backup', { id: shown.plan.id, password, init, remember, onEvent });
    progress.hidden = true;
    file.hidden = true;
    numbers.hidden = true;
    const done = text('ui.plans.run_done', {
      new: summary.filesNew,
      changed: summary.filesChanged,
      added: size(summary.dataAdded),
    });
    // the backup is done either way; then what went of the older snapshots (BACKUP-8), none after an
    // empty snapshot (BK-13)
    if (summary.empty) {
      setFailure(status, `${done} ${text('ui.plans.run_empty')}`);
    } else if (summary.expireFailed) {
      // null: the session is gone, the sign-in shows
      const why = runFailure(summary.expireFailed, shell, shown.repository, (error) => error);
      if (why !== null) setFailure(status, `${done} ${text('ui.plans.run_expire_failed', { error: why })}`);
    } else if (summary.removed > 0) {
      setStatus(status, `${done} ${text('ui.plans.run_removed', { count: summary.removed })}`);
    } else {
      setStatus(status, done);
    }
  } catch (failure) {
    progress.hidden = true;
    file.hidden = true;
    numbers.hidden = true;
    setStatus(status, '');
    throw failure;
  } finally {
    running = false;
    percent = null;
    close.textContent = text('ui.plans.run_back');
    changed();
  }
}

// a run on its page with the password it needs (`withPassword`): back to the backup plans when the
// user cancelled, else its failure on the page (UX-15), `failed` is the key of its text
async function withRunPassword(options, call, failed) {
  try {
    const done = await withPassword(options, call);
    if (done === null) shell.open('plans');
  } catch (failure) {
    const said = runFailure(failure, shell, shown.repository, (error) => text(failed, { error }));
    if (said !== null) setFailure(status, said);
  }
}

// runs the backup plan on its page, asking for the password as long as it is needed; twice for a
// new repository. `repository` is the name of its repository
export async function startRun(plan, repository) {
  if (running) return;
  shown = { plan, repository, kind: 'backup' };
  showRun();
  const create = {
    hint: text('ui.plans.run_init_hint', { repository }),
    // a new repository with the password of SAK_BACKUP_REPO_PASSWORD: only when the user says so (BK-6)
    confirm: () =>
      confirmAction({
        title: text('ui.plans.run_init_environment_title', { repository }),
        message: text('ui.plans.run_init_environment', { repository }),
        action: text('ui.plans.run_init_environment_action'),
      }),
  };
  await withRunPassword(
    { title: text('ui.plans.run'), action: text('ui.plans.run_start'), create },
    (password, remember, init) => run(password, init, remember),
    'ui.plans.run_failed',
  );
}

// restores the newest snapshot of the backup plan on its page, below target or where the files were
// for null; asking for the password as long as it is needed
export async function startRestore(plan, repository, target) {
  if (running) return;
  shown = { plan, repository, kind: 'restore' };
  showRun();
  await withRunPassword(
    { title: text('ui.plans.restore'), hint: plan.name },
    (password, remember) => restore(target, password, remember),
    'ui.plans.restore_failed',
  );
}

// one restore of restic, maybe skipping files; it throws the error it ended with
async function restore(target, password, remember) {
  running = true;
  percent = null;
  changed();
  progress.hidden = true;
  file.hidden = true;
  numbers.hidden = true;
  skipped.replaceChildren();
  close.textContent = text('ui.plans.run_back');
  setStatus(status, text('ui.plans.restoring', { name: shown.plan.name }));
  try {
    const summary = await invoke('restore_backup', { id: shown.plan.id, target, password, remember });
    if (summary.skipped.length > 0) {
      setFailure(status, text('ui.plans.restore_skipped', { count: summary.skipped.length }));
      skipped.replaceChildren(
        ...summary.skipped.map((file) => {
          const line = document.createElement('li');
          line.textContent = file;
          return line;
        }),
      );
    } else {
      const name = shown.plan.name;
      setStatus(status, target ? text('ui.plans.restored_to', { name, target }) : text('ui.plans.restored', { name }));
    }
  } catch (failure) {
    setStatus(status, '');
    throw failure;
  } finally {
    running = false;
    changed();
  }
}
