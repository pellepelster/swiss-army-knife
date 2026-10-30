// the plan of a deployment and its run, an apply or a destroy, both with the same resource rows.
// The plan of a create or a destroy has cancel, force deploy (always: the playbook runs again)
// and deploy changes (only when a resource is not up to date); a deploy is planned and started on
// the page of its deployment (deployment.js), with the same rows. The run shows each resource and each task of the playbook
// as rust works on it; done only when every resource was done, cancel stops the run and leads to
// the list of the deployments
import { openDeployment } from './password.js';
import { show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const planList = document.getElementById('plan-resources');
const planStatus = document.getElementById('plan-status');
const deployButton = document.getElementById('plan-deploy');
const forceButton = document.getElementById('plan-force');
const runList = document.getElementById('run-resources');
const runStatus = document.getElementById('run-status');
const doneButton = document.getElementById('run-done');

// the text of an apply's result, as the command line has it (applied_key in rust): a resource
// created anew reads as created, one that stays as up-to-date
const APPLIED = {
  created: 'shared.done.created',
  recreated: 'shared.done.created',
  updated: 'shared.done.updated',
  unchanged: 'shared.resource_status.up_to_date',
};

// the led of the theme for the diff status of a resource
const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

// the plan shown, what it is of (the deployment as rust takes it) and where its cancel leads
let shownPlan = null;
let planned = null;
let cancelPlan = null;
// the run: its deployment, its rows by their resource, where done leads
let running = null;
const rows = new Map();

// the text of `key` for the intent: `<key>` for a create, `<key>_deploy` or `<key>_destroy` else
function intentText(key, intent) {
  return text(intent === 'create' ? key : `${key}_${intent}`);
}

function setStatus(element, message, failed = false) {
  element.textContent = message;
  element.classList.toggle('status-failed', failed);
}

// a resource of the plan: its kind, name and resource status, then what the plan does to it.
// Rust decides the action and the working text (`shared.working.<working>`) that the row shows when the
// run starts
export function row({ kind, name: resourceName, resourceStatus, action, working, status: state, changes }) {
  const item = document.createElement('li');
  item.className = 'resource';
  item.dataset.status = state;
  item.dataset.working = working;
  const mark = document.createElement('span');
  mark.className = 'resource-state sak-led';
  mark.dataset.sakState = LEDS[state] ?? 'neutral';
  mark.setAttribute('aria-hidden', 'true');
  const kindLabel = document.createElement('span');
  kindLabel.className = 'resource-kind';
  kindLabel.textContent = text(`shared.deployments.resource.${kind}`);
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = resourceName;
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  const status = text(`shared.resource_status.${resourceStatus}`);
  detail.textContent = action ? `${status}, ${text(`shared.plan.${action}`)}` : status;
  item.append(mark, kindLabel, name, detail);
  // the provisioning tells no more than that it runs
  for (const change of kind === 'provisioning' ? [] : changes) {
    const line = document.createElement('span');
    line.className = 'resource-change';
    line.textContent = change;
    item.append(line);
  }
  return item;
}

// the plan of `deployment` (what rust takes, with its intent); `onCancel` opens what was before
export function showPlan(plan, deployment, onCancel) {
  shownPlan = plan;
  planned = deployment;
  cancelPlan = onCancel;
  document.getElementById('plan-name').textContent = plan.name;
  document.getElementById('plan-intro').textContent = intentText('shared.plan.intro', deployment.intent);
  planList.replaceChildren(...plan.resources.map(row));
  // deploy changes only when there is something to change
  deployButton.hidden = plan.resources.every((resource) => resource.resourceStatus === 'up_to_date');
  setStatus(planStatus, '');
  show('view-plan');
}

// an event of the run on the row of its resource: busy with its working text while rust works on
// it, ok when done, neutral when destroyed. The start of the provisioning and the wait for the
// deployment show in the status line, the tasks of the playbook on the row of the provisioning
function onEvent(message) {
  if (message.event === 'provisioning') {
    setStatus(runStatus, message.message);
    return;
  }
  const item = rows.get(message.resource);
  if (!item) {
    return;
  }
  const led = item.querySelector('.resource-state');
  const detail = item.querySelector('.resource-detail');
  // the state of the run from now on, no more the one of the plan: its colour follows it
  item.dataset.status = { started: 'working', taskStarted: 'working', provisionFailed: 'failed' }[message.event] ?? 'done';
  switch (message.event) {
    case 'started':
      led.dataset.sakState = 'busy';
      detail.textContent = text(`shared.working.${item.dataset.working}`);
      break;
    case 'applied':
      led.dataset.sakState = 'ok';
      detail.textContent = text(APPLIED[message.applied]);
      break;
    case 'destroyed':
      led.dataset.sakState = 'neutral';
      detail.textContent = text(message.gone ? 'shared.done.destroyed' : 'shared.plan.already_deleted');
      break;
    case 'taskStarted':
      led.dataset.sakState = 'busy';
      detail.textContent = message.task;
      break;
    case 'provisioned':
      led.dataset.sakState = 'ok';
      detail.textContent = text('ui.plan.provisioning.all_ok');
      break;
    case 'provisioningUpToDate':
      led.dataset.sakState = 'ok';
      detail.textContent = text('ui.plan.provisioning.up_to_date');
      break;
    case 'provisionFailed':
      led.dataset.sakState = 'danger';
      detail.textContent = message.task
        ? text('ui.plan.provisioning.failed').replaceAll('%{task}', message.task).replaceAll('%{message}', message.message)
        : message.message;
      break;
  }
}

// runs `deployment` with the rows of `plan`; `afterDone` is what done opens. `start` starts the
// apply in rust with the channel of its events: the deployment as it is, or the checked one
async function run(plan, deployment, afterDone, start = (channel) => invoke('create_deployment', { deployment, onEvent: channel })) {
  running = { deployment, afterDone };
  const { intent } = deployment;
  document.getElementById('run-title').textContent = text(`ui.plan.title_${intent}`);
  document.getElementById('run-intro').textContent = intentText('shared.plan.intro', intent);
  document.getElementById('run-name').textContent = plan.name;
  rows.clear();
  runList.replaceChildren(
    ...plan.resources.map((resource) => {
      const item = row(resource);
      rows.set(resource.resource, item);
      return item;
    }),
  );
  doneButton.disabled = true;
  const withData = intent === 'destroy' && deployment.deleteData ? '_with_data' : '';
  setStatus(runStatus, text({ create: 'ui.plan.creating', deploy: 'ui.plan.deploying', destroy: `ui.plan.destroying${withData}` }[intent]));
  show('view-run');
  const channel = new Channel();
  channel.onmessage = onEvent;
  const current = running;
  try {
    await start(channel);
    if (running === current) {
      setStatus(runStatus, text(intent === 'destroy' ? `ui.plan.destroyed${withData}` : 'ui.plan.provisioned'));
      doneButton.disabled = false;
      doneButton.focus();
    }
  } catch (error) {
    // only cancel leads on after a failure; a failure a row shows already is not said again
    if (running === current) {
      const shown = [...rows.values()].some((item) => item.dataset.status === 'failed');
      setStatus(runStatus, shown ? '' : String(error), !shown);
    }
  }
}

// an apply of the plan shown, forced or not
function apply(force) {
  const deployment = { ...planned, force };
  run(shownPlan, deployment, () => showApplied(deployment));
}

deployButton.addEventListener('click', () => apply(false));
forceButton.addEventListener('click', () => apply(true));
document.getElementById('plan-cancel').addEventListener('click', () => cancelPlan?.());

// the deploy of the last check of a deployment's page (rust keeps what it checked), forced or
// not; done opens the page of the deployment again
export function runChecked(plan, name, password, force) {
  const deployment = { name, password, intent: 'deploy', deleteData: false };
  run(plan, deployment, () => showApplied(deployment), (channel) => invoke('apply_checked', { force, onEvent: channel }));
}

// a destroy: its plan for the rows, then the run; done opens the list
export async function runDestroy(deployment) {
  const plan = await invoke('plan_deployment', { deployment });
  await run(plan, deployment, showList);
}

async function showList() {
  const { showDeployments } = await import('./deployments.js');
  await showDeployments();
}

// the page of the deployment just applied, as the list finds it now; the list when it is not
// there
async function showApplied(deployment) {
  try {
    const deployments = await invoke('list_deployments');
    const applied = deployments.find((found) => found.name === deployment.name.trim());
    if (applied) {
      openDeployment(applied, deployment.password);
      const { showDeployment } = await import('./deployment.js');
      showDeployment(applied);
      return;
    }
  } catch {
    // the list says what failed
  }
  await showList();
}

doneButton.addEventListener('click', () => running?.afterDone());
document.getElementById('run-cancel').addEventListener('click', async () => {
  running = null;
  invoke('cancel_apply').catch(() => {});
  await showList();
});
