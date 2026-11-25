import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '@sak/testing';
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

  const { page, open, deleteFromRow } = pageHelpers(() => harness);

  it('lists the repositories with their sizes and their total', async () => {
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
    // no create action: the app Restic Backup creates the repositories
    expect(page().querySelector('.sak-icon-plus')).toBeNull();
  });

  it('deletes a repository from its row and loads the list again (BACKUP-9)', async () => {
    await open('/backups');
    http
      .expectOne('/api/v1/restic/repositories?offset=0&limit=50')
      .flush(pageOf([laptop, desktop]));
    await harness.fixture.whenStable();

    await deleteFromRow('laptop');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/restic/repositories/1' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/restic/repositories?offset=0&limit=50').flush(pageOf([desktop]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid="repository-laptop"]')).toBeNull();
  });

  it('says in its row when a repository could not be deleted', async () => {
    await open('/backups');
    http.expectOne('/api/v1/restic/repositories?offset=0&limit=50').flush(pageOf([laptop]));
    await harness.fixture.whenStable();

    await deleteFromRow('laptop');
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/restic/repositories/1' })
      .flush(null, { status: 500, statusText: 'Error' });
    await harness.fixture.whenStable();
    expect(
      page().querySelector('[data-testid="repository-laptop"] [role=alert]')?.textContent,
    ).toContain('The repository laptop could not be deleted.');
  });

  it('says how to get a repository when there is none', async () => {
    await open('/backups');
    http.expectOne('/api/v1/restic/repositories?offset=0&limit=50').flush(pageOf([]));
    await harness.fixture.whenStable();
    expect(page().textContent).toContain(
      'No repositories yet. Create one in the app Restic Backup.',
    );
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
