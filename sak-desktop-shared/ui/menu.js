// a menu behind a button, the same in each desktop app: `toggle` opens and closes `list`, a
// `.dropdown-menu` with `role="menu"` whose entries are buttons with `role="menuitem"`. One menu is
// open at a time. Its first entry has the focus when it opens; arrow up and down, Home and End move
// between the entries that are not disabled; Tab leaves the menu closed; Escape closes it and gives
// the focus back to the toggle; a click on an entry or next to the menu closes it. What an entry
// does is its own click handler

// the toggle of each menu, by its list
const toggles = new WeakMap();
// the list of the menu that is open, null while none is
let opened = null;

function entries(list) {
  return [...list.querySelectorAll('[role=menuitem]:not(:disabled)')];
}

function open(list) {
  closeMenu();
  opened = list;
  list.classList.add('show');
  toggles.get(list).setAttribute('aria-expanded', 'true');
  entries(list)[0]?.focus();
}

// closes the menu that is open; `focusToggle` when the focus must not stay on a hidden entry
export function closeMenu({ focusToggle = false } = {}) {
  if (!opened) return;
  const list = opened;
  const toggle = toggles.get(list);
  opened = null;
  const focused = list.contains(document.activeElement);
  list.classList.remove('show');
  toggle.setAttribute('aria-expanded', 'false');
  if (focusToggle && focused) toggle.focus();
}

// the toggle of the closed menu `element` is an entry of, null for every other element: where the
// focus goes when the entry is hidden, e.g. after a dialog the entry opened
export function toggleOf(element) {
  const list = element?.closest?.('[role=menu]');
  if (!list || list === opened) return null;
  return toggles.get(list) ?? null;
}

export function menu(toggle, list) {
  toggles.set(list, toggle);
  toggle.setAttribute('aria-expanded', 'false');
  toggle.addEventListener('click', () => {
    if (opened === list) closeMenu();
    else open(list);
  });
  list.addEventListener('keydown', (event) => {
    const items = entries(list);
    const at = items.indexOf(document.activeElement);
    const next = {
      ArrowDown: (at + 1) % items.length,
      ArrowUp: (at - 1 + items.length) % items.length,
      Home: 0,
      End: items.length - 1,
    }[event.key];
    if (next !== undefined) {
      event.preventDefault();
      items[next]?.focus();
    } else if (event.key === 'Tab') {
      closeMenu();
    }
  });
  // after the handler of the entry itself: the focus stays where the entry put it, else the toggle
  // takes it from the hidden entry
  list.addEventListener('click', (event) => {
    if (event.target.closest('[role=menuitem]')) closeMenu({ focusToggle: true });
  });
}

document.addEventListener('click', (event) => {
  if (!opened) return;
  if (opened.contains(event.target) || toggles.get(opened).contains(event.target)) return;
  closeMenu();
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && opened) {
    const toggle = toggles.get(opened);
    closeMenu();
    toggle.focus();
  }
});
