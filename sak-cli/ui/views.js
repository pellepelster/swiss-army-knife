// the views of index.html and their texts; rust gives the texts in the language of the system
const { invoke } = window.__TAURI__.core;

let texts = {};

export async function loadTexts() {
  const [locale, uiTexts] = await Promise.all([invoke('locale'), invoke('ui_texts')]);
  document.documentElement.lang = locale;
  texts = uiTexts;
  for (const element of document.querySelectorAll('[data-text]')) {
    element.textContent = text(element.dataset.text);
  }
}

// the text of `key` in the language of the system, its `%{name}` placeholders filled from `values`
export function text(key, values = {}) {
  return Object.entries(values).reduce((filled, [name, value]) => filled.replaceAll(`%{${name}}`, value), texts[key] ?? key);
}

// a status line of a view: what happens or happened
export function setStatus(element, message) {
  element.textContent = message;
  element.classList.remove('status-failed');
}

// a status line of a view that says what failed, in the colour of danger
export function setFailure(element, message) {
  element.textContent = message;
  element.classList.add('status-failed');
}

// each show of a view is one visit; work that started on a visit (a check, a run, a maintenance)
// updates its view only while that visit lasts, `isCurrent`
let visit = 0;

export function show(id) {
  visit += 1;
  for (const view of document.querySelectorAll('.view')) {
    view.hidden = view.id !== id;
  }
}

// the visit of the view shown now
export function currentVisit() {
  return visit;
}

// whether the view of the visit `seen` is still the one shown
export function isCurrent(seen) {
  return seen === visit;
}

// the pages by their name and how each opens: a page opens another by its name (`navigate`), so no
// page imports the one that imports it
const pages = new Map();

export function register(name, open) {
  pages.set(name, open);
}

export function navigate(name, ...args) {
  const open = pages.get(name);
  if (!open) {
    throw new Error(`no page ${name}`);
  }
  return open(...args);
}
