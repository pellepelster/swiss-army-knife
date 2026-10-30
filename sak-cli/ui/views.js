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

// a status line of a view: what happens or happened, in the colour of danger when it failed
export function setStatus(element, message, failed = false) {
  element.textContent = message;
  element.classList.toggle('status-failed', failed);
}

export function show(id) {
  for (const view of document.querySelectorAll('.view')) {
    view.hidden = view.id !== id;
  }
}
