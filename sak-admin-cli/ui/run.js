// the plan of a create or of a new config, and the run of an apply or a destroy, with the same
// resource rows. A destroy runs without this plan, a check of deployment.js is its own plan. Done
// only when every resource was done; cancel stops the run
import { openDeployment } from './password.js';
import { currentVisit, isCurrent, navigate, setFailure, setStatus, show, text } from './views.js';

const { invoke, Channel } = window.__TAURI__.core;

const planList = document.getElementById('plan-resources');
const planStatus = document.getElementById('plan-status');
const deployButton = document.getElementById('plan-deploy');
const forceButton = document.getElementById('plan-force');
const runList = document.getElementById('run-resources');
const runStatus = document.getElementById('run-status');
const doneButton = document.getElementById('run-done');

const LEDS = { up_to_date: 'ok', missing: 'info', parent_missing: 'info', has_changes: 'warn', duplicate: 'danger' };

let shownPlan = null;
let planned = null;
let cancelPlan = null;
// rows by their resource
let running = null;
const rows = new Map();

// `<key>` for a create, `<key>_<intent>` else
function intentText(key, intent) {
  return text(intent === 'create' ? key : `${key}_${intent}`);
}


// rust decides the action and the working text (`common.working.<working>`) the row shows when the
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
  kindLabel.textContent = text(`common.deployments.resource.${kind}`);
  const name = document.createElement('span');
  name.className = 'resource-name';
  name.textContent = resourceName;
  const detail = document.createElement('span');
  detail.className = 'resource-detail';
  const status = text(`common.resource_status.${resourceStatus}`);
  detail.textContent = action ? `${status}, ${text(`common.plan.${action}`)}` : status;
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

// `onCancel` opens what was before
export function showPlan(plan, deployment, onCancel) {
  shownPlan = plan;
  planned = deployment;
  cancelPlan = onCancel;
  document.getElementById('plan-name').textContent = plan.name;
  document.getElementById('plan-intro').textContent = intentText('common.plan.intro', deployment.intent);
  planList.replaceChildren(...plan.resources.map(row));
  // a create ends with the label it started with (UX-2.2)
  deployButton.textContent = text(deployment.intent === 'create' ? 'ui.deployments.create' : 'ui.plan.deploy_changes');
  deployButton.disabled = plan.resources.every((resource) => resource.resourceStatus === 'up_to_date');
  setStatus(planStatus, '');
  show('view-plan');
}

// the start of the provisioning and the wait for the deployment show in the status line, the tasks of
// the playbook on the row of the provisioning
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
  // from now on the state of the run, no more the one of the plan
  item.dataset.status = { started: 'working', taskStarted: 'working', provisionFailed: 'failed' }[message.event] ?? 'done';
  switch (message.event) {
    case 'started':
      led.dataset.sakState = 'busy';
      detail.textContent = text(`common.working.${item.dataset.working}`);
      break;
    // the same text as the command line
    case 'applied':
      led.dataset.sakState = 'ok';
      detail.textContent = text(message.text);
      break;
    case 'destroyed':
      led.dataset.sakState = 'neutral';
      detail.textContent = text(message.text);
      break;
    case 'taskStarted':
      led.dataset.sakState = 'busy';
      detail.textContent = message.task;
      break;
    case 'provisionFailed':
      led.dataset.sakState = 'danger';
      detail.textContent = message.task
        ? text('ui.plan.provisioning.failed', { task: message.task, message: message.message })
        : message.message;
      break;
  }
}

// `afterDone` is what done opens; `start` starts the apply in rust with the channel of its events
async function run(plan, deployment, afterDone, start = (channel) => invoke('apply_deployment', { deployment, onEvent: channel })) {
  running = { deployment, afterDone };
  const { intent } = deployment;
  document.getElementById('run-title').textContent = text(`ui.plan.title_${intent}`, { name: plan.name });
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
  const visit = currentVisit();
  const channel = new Channel();
  channel.onmessage = onEvent;
  try {
    await start(channel);
    if (isCurrent(visit)) {
      setStatus(runStatus, text(intent === 'destroy' ? `ui.plan.destroyed${withData}` : 'ui.plan.provisioned'));
      doneButton.disabled = false;
      doneButton.focus();
    }
  } catch (error) {
    // only cancel leads on after a failure; a failure a row shows already is not said again
    if (isCurrent(visit)) {
      const shown = [...rows.values()].some((item) => item.dataset.status === 'failed');
      if (shown) {
        setStatus(runStatus, '');
      } else {
        setFailure(runStatus, String(error));
      }
    }
  }
}

function apply(force) {
  const deployment = { ...planned, force };
  run(shownPlan, deployment, () => showApplied(deployment));
}

deployButton.addEventListener('click', () => apply(false));
forceButton.addEventListener('click', () => apply(true));
document.getElementById('plan-cancel').addEventListener('click', () => cancelPlan?.());

// rust keeps what it checked, `plan.check` names it
export function runChecked(plan, name, password, force) {
  const deployment = { name, password, intent: 'deploy', deleteData: false };
  run(plan, deployment, () => showApplied(deployment), (channel) => invoke('apply_checked', { deploy: { check: plan.check, force }, onEvent: channel }));
}

export async function runDestroy(plan, deployment) {
  await run(plan, deployment, showList);
}

async function showList() {
  await navigate('deployments');
}

// the list when the deployment is not in it
async function showApplied(deployment) {
  try {
    const deployments = await invoke('list_deployments');
    const applied = deployments.find((found) => found.name === deployment.name.trim());
    if (applied) {
      openDeployment(applied, deployment.password);
      navigate('deployment', applied);
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
