// the session kept from the start before or the sign-in (shared/login.js), then the pages behind the navigation on the left and the menu of
// the user at the top right; rust keeps the session (sak-desktop-shared)
import { displayName, showLogin, signOut, startSession } from './shared/login.js';
import { closeMenu, menu } from './shared/menu.js';
import { runChecks } from './shared/checks.js';
import { showVersion } from './shared/update.js';
import { loadTexts, show, text } from './shared/views.js';
import { initPlans, showPlans } from './plans.js';
import { initRepositories, showRepositories } from './repositories.js';
import { initRun } from './run.js';
import { initSnapshots, showSnapshots } from './snapshots.js';

const menuToggle = document.getElementById('user-menu-toggle');

const LOGIN = { title: 'ui.title', intro: 'ui.intro', onSignedIn: showShell };

function login() {
  showLogin(LOGIN);
}

function showShell(user) {
  const name = displayName(user, text('ui.menu.admin'));
  document.getElementById('user-name').textContent = name;
  menuToggle.setAttribute('aria-label', text('ui.menu.label', { user: name }));
  closeMenu();
  openPage('repositories');
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
    for (const section of document.querySelectorAll('.shell-main .page')) {
      section.hidden = section.id !== id;
    }
  },
  signedOut() {
    showLogin(LOGIN, text('ui.login.restore.expired'));
  },
  open(page) {
    openPage(page);
  },
};
initRepositories(shell);
initPlans(shell);
initSnapshots(shell);
initRun(shell);

// the pages behind the navigation; one without a page shows that there is nothing yet
const PAGES = { repositories: showRepositories, plans: showPlans, snapshots: showSnapshots };

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

// the menu of the user (shared/menu.js), which closes itself after a click on an entry
menu(menuToggle, document.getElementById('user-menu'));

document.getElementById('sign-out').addEventListener('click', async () => {
  await signOut();
  login();
});

await loadTexts();
for (const element of document.querySelectorAll('[data-text-label]')) {
  element.setAttribute('aria-label', text(element.dataset.textLabel));
}
await showVersion();
// restic first (shared/checks.js): without it nothing can be backed up; after a failure the splash
// screen stays
await runChecks();
await startSession(LOGIN);
