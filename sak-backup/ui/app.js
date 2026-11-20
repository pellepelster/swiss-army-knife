// the sign-in (shared/login.js), then the pages behind the navigation on the left and the menu of
// the user at the top right; rust keeps the session (sak-desktop-shared)
import { displayName, showLogin, signedInUser, signOut } from './shared/login.js';
import { loadTexts, show, text } from './shared/views.js';

const menuToggle = document.getElementById('user-menu-toggle');
const menu = document.getElementById('user-menu');

function login() {
  showLogin({ title: 'ui.title', intro: 'ui.intro', onSignedIn: showShell });
}

function showShell(user) {
  const name = displayName(user, text('ui.menu.admin'));
  document.getElementById('user-name').textContent = name;
  menuToggle.setAttribute('aria-label', text('ui.menu.label', { user: name }));
  closeMenu();
  openPage('backups');
  show('view-shell');
}

function openPage(page) {
  for (const link of document.querySelectorAll('#nav [data-page]')) {
    const active = link.dataset.page === page;
    link.classList.toggle('active', active);
    if (active) {
      link.setAttribute('aria-current', 'page');
      document.getElementById('page-title').textContent = link.textContent;
    } else {
      link.removeAttribute('aria-current');
    }
  }
}

document.getElementById('nav').addEventListener('click', (event) => {
  const link = event.target.closest('[data-page]');
  if (link) openPage(link.dataset.page);
});

function closeMenu() {
  menu.classList.remove('show');
  menuToggle.setAttribute('aria-expanded', 'false');
}

menuToggle.addEventListener('click', (event) => {
  event.stopPropagation();
  const open = !menu.classList.contains('show');
  menu.classList.toggle('show', open);
  menuToggle.setAttribute('aria-expanded', String(open));
  if (open) menu.querySelector('[role=menuitem]').focus();
});

document.addEventListener('click', (event) => {
  if (!menu.contains(event.target)) closeMenu();
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && menu.classList.contains('show')) {
    closeMenu();
    menuToggle.focus();
  }
});

document.getElementById('sign-out').addEventListener('click', async () => {
  closeMenu();
  await signOut();
  login();
});

await loadTexts();
for (const element of document.querySelectorAll('[data-text-label]')) {
  element.setAttribute('aria-label', text(element.dataset.textLabel));
}
const user = await signedInUser();
if (user) {
  showShell(user);
} else {
  login();
}
