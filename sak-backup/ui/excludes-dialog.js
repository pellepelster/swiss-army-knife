// the dialog of the excludes of a backup plan: what a run leaves out, the patterns of restic one a
// line with comments, as the plan keeps them. Presets of this computer copy their lines into the list
// (core/presets.rs), a file of patterns can be imported, and the preview walks the paths of the plan
// with the rules of restic and tells what a run would leave out (core/excludes.rs). It answers
// { excludes, excludeCaches }, or null when it is cancelled; a plan of another computer is only shown
import { size } from './run.js';
import { toggleOf } from './shared/menu.js';
import { setFailure, setStatus, text } from './shared/views.js';

const { invoke } = window.__TAURI__.core;

const $ = (id) => document.getElementById(id);
const dialog = $('excludes-dialog');
const form = $('excludes-form');
const patterns = $('excludes-text');
const count = $('excludes-count');
const error = $('excludes-error');
const caches = $('excludes-caches');
const presetList = $('excludes-presets');
const previewButton = $('excludes-preview');
const importButton = $('excludes-import');
const previewStatus = $('excludes-preview-status');
const previewList = $('excludes-preview-list');
const done = $('excludes-done');

let answer = null;
let opener = null;
// the paths of the plan, which the preview walks
let paths = [];
let presets = null;

// the patterns restic reads: a line without its spaces, none for a blank line or a comment
export function patternLines(excludes) {
  return excludes
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
}

function close(value) {
  answer?.(value);
  answer = null;
  dialog.close();
  // the opener when it is still there, else the toggle of a menu it was in
  (opener?.isConnected && !opener.closest('[hidden]') ? opener : toggleOf(opener))?.focus();
}

function counted() {
  const lines = patternLines(patterns.value);
  count.textContent = text('ui.excludes.count', { count: lines.length });
  error.textContent = '';
  for (const button of presetList.querySelectorAll('button')) showPreset(button, lines);
}

// a preset whose patterns are all in the list is added; adding one copies only what is missing
function showPreset(button, lines = patternLines(patterns.value)) {
  const preset = presets.find((each) => each.id === button.dataset.preset);
  const added = preset.patterns.every((pattern) => lines.includes(pattern));
  button.disabled = added || patterns.readOnly;
  button.title = added ? text('ui.excludes.preset_added') : '';
  button.setAttribute('aria-pressed', String(added));
}

function addPreset(preset) {
  const lines = patternLines(patterns.value);
  const missing = preset.patterns.filter((pattern) => !lines.includes(pattern));
  if (!missing.length) return;
  const before = patterns.value.replace(/\s+$/, '');
  patterns.value = [before, before ? '' : null, `### ${preset.name}`, ...missing].filter((line) => line !== null).join('\n');
  patterns.dispatchEvent(new Event('input'));
}

async function showPresets() {
  presets ??= await invoke('exclude_presets');
  presetList.replaceChildren(
    ...presets.map((preset) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-outline-secondary btn-sm';
      button.dataset.preset = preset.id;
      button.textContent = `+ ${preset.name}`;
      button.addEventListener('click', () => addPreset(preset));
      return button;
    }),
  );
}

function clearPreview() {
  setStatus(previewStatus, '');
  previewList.replaceChildren();
}

function line(label, detail, examples = []) {
  const item = document.createElement('li');
  item.className = 'excludes-preview-item';
  const name = document.createElement('span');
  name.className = 'sak-mono';
  name.textContent = label;
  const what = document.createElement('span');
  what.className = 'hint';
  what.textContent = detail;
  item.append(name, what);
  if (examples.length) {
    const where = document.createElement('span');
    where.className = 'hint sak-mono excludes-preview-examples';
    where.textContent = examples.join('\n');
    item.append(where);
  }
  return item;
}

async function preview() {
  clearPreview();
  if (!paths.length) {
    setFailure(previewStatus, text('ui.excludes.no_paths'));
    return;
  }
  previewButton.disabled = true;
  setStatus(previewStatus, text('ui.excludes.preview_running'));
  try {
    const found = await invoke('preview_excludes', {
      paths,
      excludes: patterns.value,
      excludeCaches: caches.checked,
    });
    const backedUp = { files: found.includedFiles, size: size(found.includedBytes) };
    let said = text(found.skipped.length ? 'ui.excludes.preview_result' : 'ui.excludes.preview_nothing', backedUp);
    if (!found.complete) said = `${said} ${text('ui.excludes.preview_partial')}`;
    setStatus(previewStatus, said);
    const items = found.skipped.map((skipped) =>
      line(
        skipped.pattern === 'CACHEDIR.TAG' ? text('ui.excludes.preview_cache_folders') : skipped.pattern,
        text('ui.excludes.preview_skipped', { files: skipped.files, size: size(skipped.bytes) }),
        skipped.examples,
      ),
    );
    if (found.unused.length) items.push(line(found.unused.join('\n'), text('ui.excludes.preview_unused')));
    if (found.missing.length) items.push(line(found.missing.join('\n'), text('ui.excludes.preview_missing')));
    previewList.replaceChildren(...items);
  } catch (failure) {
    setFailure(previewStatus, text('ui.excludes.preview_failed', { error: String(failure) }));
  } finally {
    previewButton.disabled = false;
  }
}

async function importFile() {
  try {
    const [picked] = await invoke('pick_paths', { folders: false });
    if (!picked) return;
    const read = await invoke('read_excludes', { path: picked });
    const before = patterns.value.replace(/\s+$/, '');
    patterns.value = before ? `${before}\n\n${read}` : read;
    patterns.dispatchEvent(new Event('input'));
  } catch (failure) {
    error.textContent = String(failure);
  }
}

patterns.addEventListener('input', () => {
  counted();
  clearPreview();
});
caches.addEventListener('change', clearPreview);
previewButton.addEventListener('click', preview);
importButton.addEventListener('click', importFile);
$('excludes-cancel').addEventListener('click', () => close(null));
dialog.addEventListener('cancel', (event) => {
  event.preventDefault();
  close(null);
});
form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (patterns.readOnly) {
    close(null);
    return;
  }
  close({ excludes: patterns.value, excludeCaches: caches.checked });
});

// opens the dialog for a plan: its name (none for a new one), its paths, its excludes; read only
// for a plan of another computer (BACKUP-5)
export async function editExcludes({ name, paths: planPaths, excludes, excludeCaches, readOnly }) {
  opener = document.activeElement;
  paths = planPaths;
  $('excludes-title').textContent = name ? text('ui.excludes.title', { name }) : text('ui.excludes.title_new');
  patterns.value = excludes;
  patterns.readOnly = readOnly;
  caches.checked = excludeCaches;
  caches.disabled = readOnly;
  importButton.disabled = readOnly;
  previewButton.disabled = readOnly;
  done.textContent = text(readOnly ? 'ui.excludes.close' : 'ui.excludes.done');
  $('excludes-cancel').hidden = readOnly;
  clearPreview();
  try {
    await showPresets();
  } catch (failure) {
    error.textContent = String(failure);
  }
  counted();
  dialog.showModal();
  patterns.focus();
  return new Promise((resolve) => {
    answer = resolve;
  });
}
