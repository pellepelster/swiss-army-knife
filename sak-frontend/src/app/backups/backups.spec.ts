import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { APPS } from '../apps';
import { pageHelpers, pageOf } from '../testing/page';
import { backupsRoute } from './backups.routes';

const laptop = {
  id: 1,
  name: 'laptop',
  url: 'rest:https://api.example.org/restic/1/',
  size: 2_500_000,
  createdAt: '2026-11-21T08:00:00.000Z',
};
const desktop = {
  ...laptop,
  id: 2,
  name: 'desktop',
  url: 'rest:https://api.example.org/restic/2/',
  size: 1_000,
};

describe('Backups', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard of the app is left out, the tests are signed in
        provideRouter([{ ...backupsRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, open, rowMenu } = pageHelpers(() => harness);

  it('is an app of every user', () => {
    const app = APPS.find((each) => each.path === '/backups')!;
    expect(app.icon).toBe('icons/backups.svg');
    expect(app.adminOnly).toBeFalsy();
  });

  it('lists the repositories with their sizes and their total, a delete disabled', async () => {
    await open('/backups');
    http
      .expectOne('/api/v1/restic/repositories?offset=0&limit=50')
      .flush(pageOf([laptop, desktop]));
    await harness.fixture.whenStable();

    const row = page().querySelector('[data-testid="repository-laptop"]')!;
    expect(row.textContent).toContain('rest:https://api.example.org/restic/1/');
    expect(row.querySelector('[data-testid=size]')?.textContent?.trim()).toBe('2.5 MB');
    expect(
      page()
        .querySelector('[data-testid="repository-desktop"] [data-testid=size]')
        ?.textContent?.trim(),
    ).toBe('1 kB');
    expect(page().querySelector('[data-testid=total]')?.textContent?.trim()).toBe(
      '2.5 MB on this page',
    );
    // no create action: the app SAK Backup creates the repositories
    expect(page().querySelector('.sak-icon-plus')).toBeNull();

    const menu = await rowMenu('laptop');
    const remove = [...menu.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === 'Delete',
    )!;
    expect(remove.disabled).toBe(true);
    expect(remove.title).toBe('Deleting a repository is not possible yet.');
  });

  it('says how to get a repository when there is none', async () => {
    await open('/backups');
    http.expectOne('/api/v1/restic/repositories?offset=0&limit=50').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('No repositories yet. Create one in the app SAK Backup.');
    expect(page().querySelector('[data-testid=total]')).toBeNull();
  });

  it('shows a page that could not be loaded', async () => {
    await open('/backups');
    http
      .expectOne('/api/v1/restic/repositories?offset=0&limit=50')
      .flush(null, { status: 500, statusText: 'Error' });
    await harness.fixture.whenStable();
    expect(page().querySelector('.alert-danger')?.textContent).toContain(
      'The repositories could not be loaded.',
    );
  });
});
