// the backup plans (BACKUP-5, BACKUP-6): their list, a card each with a ⋮ menu, and the form that creates
// one or changes one of this computer: a repository of the user and the paths to back up. A backup plan
// of another computer opens read only
import { createRepositoryButton } from './repositories.js';
import {
  badge,
  button,
  createButton,
  disable,
  entry,
  loadFailed,
  nameField,
  repositoryOptions,
  row,
  rowDetail,
  rowName,
  saveFailed,
  signedOut,
  message,
  thisHost,
} from './rows.js';
import { onRunsChanged, runningPlan, runProgress, showRun, startRestore, startRun } from './run.js';
import { editExcludes, patternLines } from './excludes-dialog.js';
import { confirmAction } from './shared/confirm-dialog.js';
import { closeMenu } from './shared/menu.js';
import { $, invoke, setBusy, setFailure, setStatus, text } from './shared/views.js';

const list = $('plans-list');
const status = $('plans-status');
const form = $('plan-form');
const name = $('plan-name');
const repository = $('plan-repository');
const pathList = $('plan-paths');
const formStatus = $('plan-status');
const submit = $('plan-submit');

let shell = null;
// the host name of this computer, a backup plan of another one is not changed here
let host = null;
// the backup plan the form changes, null while it creates one; its paths as the form has them
let editing = null;
let paths = [];

// how many snapshots a run keeps (BACKUP-8): the fields by the name of the api, what a new plan keeps
const KEEP = {
  keepLast: $('plan-keep-last'),
  keepDaily: $('plan-keep-daily'),
  keepWeekly: $('plan-keep-weekly'),
  keepMonthly: $('plan-keep-monthly'),
  keepYearly: $('plan-keep-yearly'),
};
const NEW_PLAN_KEEPS = { keepDaily: 7, keepWeekly: 4, keepMonthly: 12 };

export function initPlans(pageShell) {
  shell = pageShell;
}

export async function showPlans() {
  shell.title(text('ui.nav.plans'));
  const create = createButton(text('ui.plans.create'), () => showForm(null, repositories));
  shell.toolbar(create);
  // a backup plan names a repository: none to choose before they are loaded
  disable(create, text('ui.plans.loading'));
  shell.page('page-plans');
  list.replaceChildren();
  setStatus(status, text('ui.plans.loading'));
  let repositories = [];
  try {
    host = await thisHost();
    const [plans, loaded] = await Promise.all([invoke('plans'), invoke('repositories')]);
    repositories = loaded;
    create.disabled = false;
    create.removeAttribute('title');
    // without a repository there is nothing to back up into: disabled, never hidden (UX-8)
    if (repositories.length === 0) disable(create, text('ui.plans.no_repository'));
    const names = new Map(repositories.map((each) => [each.id, each.name]));
    runButtons = [];
    list.replaceChildren(...plans.map((plan) => planRow(plan, names, repositories)));
    if (plans.length > 0) {
      setStatus(status, '');
    } else if (repositories.length === 0) {
      setStatus(status, `${text('ui.plans.empty')} ${text('ui.plans.no_repository')}`);
      status.append(' ', createRepositoryButton(shell));
    } else {
      setStatus(status, text('ui.plans.empty'));
    }
  } catch (error) {
    loadFailed(error, status, shell);
  }
}

function planRow(plan, names, repositories) {
  const repositoryName = names.get(plan.repositoryId) ?? String(plan.repositoryId);
  const badges = [badge(repositoryName)];
  if (plan.host !== host) badges.push(badge(text('ui.plans.other_computer', { host: plan.host })));
  const detail = rowDetail(
    plan.paths.length === 1 ? plan.paths[0] : `${plan.paths[0]} … ${text('ui.plans.paths_count', { count: plan.paths.length })}`,
    plan.paths.join('\n'),
  );
  const open = () => showForm(plan, repositories);
  const edit = entry(text('ui.plans.open'), open, {
    disabled: plan.host === host ? null : text('ui.plans.other_computer_hint', { host: plan.host }),
  });
  const restoreHere = entry(text('ui.plans.restore'), () => restore(plan, repositoryName));
  const restoreTo = entry(text('ui.plans.restore_to'), () => restoreToFolder(plan, repositoryName));
  const remove = entry(text('ui.delete'), () => deletePlan(plan), { danger: true });
  // only a backup plan of this computer runs or is restored here, one at a time, on its own page;
  // disabled, never hidden (UX-8). The row of the plan that runs opens its page again
  const start = button('btn btn-outline-secondary btn-sm', text('ui.plans.run'), (event) => {
    event.stopPropagation();
    if (runningPlan() === plan.id) showRun();
    else startRun(plan, repositoryName);
  });
  // the row of the plan that runs: striped up to its percent, the whole row when there is none
  // (style.css: .row-running), the percent by its name
  const percent = document.createElement('span');
  percent.className = 'row-percent';
  percent.hidden = true;
  const item = row(plan.name, [rowName(plan.name), percent, ...badges, detail], open, [edit, restoreHere, restoreTo, remove], [start]);
  const enable = () => {
    const runs = runningPlan() === plan.id;
    const progress = runs ? runProgress() : null;
    item.classList.toggle('row-running', runs);
    item.style.setProperty('--run-progress', `${progress ?? 100}%`);
    percent.hidden = progress === null;
    percent.textContent = progress === null ? '' : `${progress}%`;
    start.classList.toggle('btn-running', runs);
    start.disabled = false;
    start.title = '';
    start.textContent = text(runningPlan() === plan.id ? 'ui.plans.run_show' : 'ui.plans.run');
    if (plan.host !== host) disable(start, text('ui.plans.run_other_computer', { host: plan.host }));
    else if (runningPlan() !== null && runningPlan() !== plan.id) disable(start, text('ui.plans.run_busy'));
    for (const each of [restoreHere, restoreTo]) {
      each.disabled = false;
      each.title = '';
      if (plan.host !== host) disable(each, text('ui.plans.restore_other_computer', { host: plan.host }));
      else if (runningPlan() !== null) disable(each, text('ui.plans.run_busy'));
    }
    // the app changes only the backup plans of this computer (BACKUP-5), never the one that runs
    remove.disabled = false;
    remove.title = '';
    if (plan.host !== host) disable(remove, text('ui.plans.delete_other_computer', { host: plan.host }));
    else if (runningPlan() === plan.id) disable(remove, text('ui.plans.run_busy'));
  };
  enable();
  runButtons.push(enable);
  return item;
}

// a backup plan goes with its paths, its repository and its snapshots stay: asked first, then the
// list again without it
async function deletePlan(plan) {
  const confirmed = await confirmAction({
    title: text('ui.plans.delete_title', { name: plan.name }),
    message: text('ui.plans.delete_confirm', { name: plan.name }),
    action: text('ui.delete'),
    danger: true,
  });
  if (!confirmed) return;
  try {
    await invoke('delete_plan', { id: plan.id });
    await showPlans();
  } catch (error) {
    if (!signedOut(error, shell)) setFailure(status, text('ui.plans.delete_failed', { error: message(error) }));
  }
}

// a restore where the files were changes files of this computer: asked first
async function restore(plan, repository) {
  const confirmed = await confirmAction({
    title: text('ui.plans.restore'),
    message: text('ui.plans.restore_confirm', { name: plan.name }),
    action: text('ui.plans.restore'),
  });
  if (confirmed) startRestore(plan, repository, null);
}

// a restore below a folder the user picks: each file under its whole path there
async function restoreToFolder(plan, repository) {
  try {
    const target = await invoke('pick_folder');
    if (target) startRestore(plan, repository, target);
  } catch (error) {
    setFailure(status, String(error));
  }
}

// the buttons that start a run, told when one starts or ends
let runButtons = [];
onRunsChanged(() => runButtons.forEach((enable) => enable()));

// the form: empty to create a backup plan of this computer, filled to change one; read only for one of
// another computer (BACKUP-5)
// what a run of the plan in the form leaves out, edited in its dialog
let excludes = '';
let excludeCaches = false;

function showExcludes(readOnly) {
  const lines = patternLines(excludes).length;
  const parts = [lines ? text('ui.plans.excludes_summary', { count: lines }) : text('ui.plans.excludes_none')];
  if (excludeCaches) parts.push(text('ui.plans.excludes_caches_on'));
  $('plan-excludes-summary').textContent = parts.join(' · ');
  const edit = $('plan-excludes-edit');
  edit.textContent = text(readOnly ? 'ui.plans.excludes_show' : 'ui.plans.excludes_edit');
  // a plan of another computer shows its excludes without changing them
  edit.disabled = false;
}

function showForm(plan, repositories) {
  closeMenu();
  editing = plan;
  paths = [...(plan?.paths ?? [])];
  excludes = plan?.excludes ?? '';
  excludeCaches = plan?.excludeCaches ?? false;
  const readOnly = plan !== null && plan.host !== host;
  name.value = plan?.name ?? '';
  repositoryOptions(repository, repositories, plan?.repositoryId);
  for (const [name, field] of Object.entries(KEEP)) field.value = String((plan ?? NEW_PLAN_KEEPS)[name] ?? '');
  for (const error of form.querySelectorAll('.field-error')) error.textContent = '';
  setStatus(formStatus, '');
  $('plan-host').textContent = readOnly
    ? text('ui.plans.other_computer_hint', { host: plan.host })
    : text('ui.plans.this_computer', { host });
  for (const element of form.querySelectorAll('input, select, fieldset button')) element.disabled = readOnly;
  submit.textContent = text(plan ? 'ui.save' : 'ui.plans.create');
  submit.disabled = readOnly;
  shell.title(text(plan ? 'ui.plans.edit' : 'ui.plans.create'));
  shell.toolbar();
  shell.page('page-plan');
  showPaths(readOnly);
  showExcludes(readOnly);
  if (!readOnly) name.focus();
}

function showPaths(readOnly = false) {
  pathList.replaceChildren(
    ...paths.map((path) => {
      const item = document.createElement('li');
      item.className = 'path';
      const label = document.createElement('span');
      label.className = 'sak-mono';
      label.textContent = path;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'btn btn-outline-secondary btn-sm';
      remove.textContent = '×';
      remove.setAttribute('aria-label', text('ui.plans.remove_path', { path }));
      remove.disabled = readOnly;
      remove.addEventListener('click', () => {
        paths = paths.filter((each) => each !== path);
        showPaths();
      });
      item.append(label, remove);
      return item;
    }),
  );
}

// adds paths once each, in the order they come
function add(added) {
  for (const path of added) {
    if (!paths.includes(path)) paths.push(path);
  }
  $('plan-path-error').textContent = '';
  showPaths();
}

$('plan-excludes-edit').addEventListener('click', async () => {
  const readOnly = editing !== null && editing.host !== host;
  const edited = await editExcludes({ name: name.value.trim(), excludes, excludeCaches, readOnly });
  if (!edited) return;
  ({ excludes, excludeCaches } = edited);
  showExcludes(readOnly);
});

async function pick(folders) {
  try {
    add(await invoke('pick_paths', { folders }));
  } catch (error) {
    setFailure(formStatus, String(error));
  }
}

$('plan-add-folder').addEventListener('click', () => pick(true));
$('plan-add-file').addEventListener('click', () => pick(false));

const checkName = nameField(name, $('plan-name-error'), submit);

// the keep setting of the form, each number from 1 to 1000 or none; null when one is not
function keepOf() {
  const keep = {};
  for (const [name, field] of Object.entries(KEEP)) {
    const value = field.value.trim();
    if (value === '') {
      keep[name] = null;
    } else if (/^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 1000) {
      keep[name] = Number(value);
    } else {
      return null;
    }
  }
  return keep;
}

// checked as they are typed, not only on save (UX-6)
for (const field of Object.values(KEEP)) {
  field.addEventListener('input', () => {
    $('plan-keep-error').textContent = keepOf() === null ? text('ui.plans.invalid_keep') : '';
  });
}

// the errors of the api by the field they belong to (UX-15), the others above the buttons
const FIELD_OF = {
  'restic.invalid_name': 'name',
  'restic.plan_exists': 'name',
  'restic.unknown_repository': 'repository',
  'restic.no_paths': 'path',
  'restic.too_many_paths': 'path',
  'restic.double_path': 'path',
  'restic.invalid_path': 'path',
  'restic.invalid_keep': 'keep',
};

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const named = checkName();
  if (paths.length === 0) $('plan-path-error').textContent = text('ui.plans.no_paths');
  const keep = keepOf();
  if (keep === null) $('plan-keep-error').textContent = text('ui.plans.invalid_keep');
  if (!named || paths.length === 0 || keep === null) return;
  setBusy(form, true);
  setStatus(formStatus, text('ui.saving'));
  // the excludes go along only when there are some, or the plan had some: a SAK instance from before
  // the excludes refuses a request with a field it does not know
  const sendsExcludes = Boolean(excludes || excludeCaches || editing?.excludes || editing?.excludeCaches);
  const plan = {
    name: name.value.trim(),
    repositoryId: Number(repository.value),
    host,
    paths,
    ...keep,
    ...(sendsExcludes ? { excludes, excludeCaches } : {}),
  };
  try {
    if (editing) {
      await invoke('update_plan', { id: editing.id, plan });
    } else {
      await invoke('create_plan', { plan });
    }
    await showPlans();
  } catch (error) {
    if (signedOut(error, shell)) return;
    setStatus(formStatus, '');
    if (sendsExcludes && isMalformed(error)) setFailure(formStatus, text('ui.plans.excludes_unsupported'));
    else saveFailed(error, FIELD_OF, (field) => $(`plan-${field}-error`), formStatus);
  } finally {
    setBusy(form, false);
  }
});

// the answer of a SAK instance that could not read the request, e.g. one from before the excludes
function isMalformed(error) {
  return error?.kind === 'refused' && error.status === 400 && error.errors.some((each) => each.code === 'api.malformed_request');
}

$('plan-cancel').addEventListener('click', () => showPlans());
