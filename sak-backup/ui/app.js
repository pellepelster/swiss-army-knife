// the session kept from the start before or the sign-in (shared/login.js), then the pages behind the navigation on the left and the menu of
// the user at the top right; rust keeps the session (sak-desktop-shared)
import { displayName, showLogin, signOut, startSession } from './shared/login.js';
import { loadTexts, show, text } from './shared/views.js';
import { initRepositories, showRepositories } from './repositories.js';

const menuToggle = document.getElementById('user-menu-toggle');
const menu = document.getElementById('user-menu');

const LOGIN = { title: 'ui.title', intro: 'ui.intro', onSignedIn: showShell };

function login() {
  showLogin(LOGIN);
}

function showShell(user) {
  const name = displayName(user, text('ui.menu.admin'));
  document.getElementById('user-name').textContent = name;
  menuToggle.setAttribute('aria-label', text('ui.menu.label', { user: name }));
  closeMenu();
  openPage('backups');
  show('view-shell');
}

// what a page of the shell uses: the title and the actions of the header (UX-10), the section it
// shows, and the sign-in when the session is gone
const shell = {
  title(title) {
    document.getElementById('page-title').textContent = title;
  },
  toolbar(...buttons) {
    document.getElementById('page-toolbar').replaceChildren(...buttons);
  },
  page(id) {
    for (const section of document.querySelectorAll('.shell-main .page, #page-empty')) {
      section.hidden = section.id !== id;
    }
  },
  signedOut() {
    showLogin(LOGIN, text('ui.login.restore.expired'));
  },
};
initRepositories(shell);

// the pages behind the navigation; a page without one shows that there is nothing yet
const PAGES = { repositories: showRepositories };

function openPage(page) {
  for (const link of document.querySelectorAll('#nav [data-page]')) {
    const active = link.dataset.page === page;
    link.classList.toggle('active', active);
    if (active) {
      link.setAttribute('aria-current', 'page');
      shell.title(link.textContent);
    } else {
      link.removeAttribute('aria-current');
    }
  }
  if (PAGES[page]) {
    PAGES[page]();
  } else {
    shell.toolbar();
    shell.page('page-empty');
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
await startSession(LOGIN);
