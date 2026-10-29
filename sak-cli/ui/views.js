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

export function text(key) {
  return texts[key] ?? key;
}

export function show(id) {
  for (const view of document.querySelectorAll('.view')) {
    view.hidden = view.id !== id;
  }
}
