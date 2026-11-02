import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { usersRoute } from './users.routes';

const admin = { id: null, email: 'admin@example.org', name: null, configured: true };
const anna = { id: 3, email: 'anna@example.org', name: 'Anna', configured: false };
const bob = { id: 4, email: 'bob@example.org', name: null, configured: false };

describe('Users', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // the guard is left out, the tests are signed in
        provideRouter([{ ...usersRoute, canActivate: [] }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open, url, rowMenu } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;

  const list = async (users: object[]) => {
    await open('/users');
    http.expectOne('/api/v1/users?offset=0&limit=50').flush(pageOf(users));
    await harness.fixture.whenStable();
  };

  it('lists the admins and the users with their address, name and role; an admin is not deleted', async () => {
    await list([admin, anna, bob]);
    expect([...page().querySelectorAll('thead th')].map((th) => th.textContent!.trim())).toEqual([
      'E-mail',
      'Name',
      'Role',
      'Actions',
    ]);
    const rows = [...page().querySelectorAll('tbody tr')].map((row) =>
      ['email', 'name', 'role'].map((cell) =>
        row.querySelector(`[data-testid=${cell}]`)!.textContent!.trim(),
      ),
    );
    expect(rows).toEqual([
      ['admin@example.org', '–', 'admin'],
      ['anna@example.org', 'Anna', 'user'],
      ['bob@example.org', '–', 'user'],
    ]);
    // an admin is in the configuration, its delete says so (UX-8)
    const menu = await rowMenu('admin@example.org');
    expect(button('Delete', menu).disabled).toBe(true);
    expect(button('Delete', menu).title).toBe('Set by the configuration of the deployment');
    expect(button('Create user').getAttribute('href')).toBe('/users/create');
  });

  it('deletes a user from the menu of its row after a confirmation, keep is the way back', async () => {
    await list([admin, anna]);
    button('Delete', await rowMenu('anna@example.org')).click();
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('Delete anna@example.org?');
    button('Keep').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });

    button('Delete', await rowMenu('anna@example.org')).click();
    await harness.fixture.whenStable();
    button('Delete', page().querySelector('[aria-label="Delete anna@example.org"]')!).click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/users/3' }).flush(null);
    http.expectOne('/api/v1/users?offset=0&limit=50').flush(pageOf([admin]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid="user-anna@example.org"]')).toBeNull();
  });

  it('sends a user or an admin the links of the android apps from the menu of its row', async () => {
    await list([admin, anna]);
    button('Send links to the Android apps', await rowMenu('anna@example.org')).click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/users/app-link' });
    expect(request.request.body).toEqual({ email: 'anna@example.org' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=app-link-sent]')?.textContent?.trim()).toBe(
      'The links to the Android apps were sent to anna@example.org.',
    );

    // what the api tells when it fails, e.g. the mail server did not take it
    button('Send links to the Android apps', await rowMenu('admin@example.org')).click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/users/app-link' })
      .flush(
        { errors: [{ code: 'users.app_link_not_sent', message: 'The e-mail could not be sent.' }] },
        { status: 502, statusText: 'Bad Gateway' },
      );
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=app-link-sent]')).toBeNull();
    expect(page().querySelector('[role=alert]')?.textContent).toContain(
      'The e-mail could not be sent.',
    );
  });

  it('adds a user by address with an optional name, the address checked when it is left', async () => {
    await open('/users/create');
    const type = async (id: string, value: string) => {
      input(id).value = value;
      input(id).dispatchEvent(new Event('input'));
      await harness.fixture.whenStable();
    };
    // cancel and add are one group
    const group = page().querySelector('form [role=group][aria-label="Actions of the form"]')!;
    expect(
      [...group.querySelectorAll('a, button')].map((each) => each.textContent!.trim()),
    ).toEqual(['Cancel', 'Create user']);
    await type('user-email', 'anna');
    // no error while typing
    expect(input('user-email').classList.contains('is-invalid')).toBe(false);
    input('user-email').dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    expect(input('user-email').classList.contains('is-invalid')).toBe(true);
    expect(button('Create user').disabled).toBe(true);

    await type('user-email', ' anna@example.org ');
    expect(input('user-email').classList.contains('is-invalid')).toBe(false);
    await type('user-name', ' Anna ');
    button('Create user').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/users' });
    expect(request.request.body).toEqual({ email: 'anna@example.org', name: 'Anna' });
    request.flush(anna, { status: 201, statusText: 'Created' });
    await harness.fixture.whenStable();
    expect(url()).toBe('/users');
    http.expectOne('/api/v1/users?offset=0&limit=50').flush(pageOf([admin, anna]));
  });

  it('says why an address is refused, e.g. an admin of the deployment', async () => {
    await open('/users/create');
    input('user-email').value = 'admin@example.org';
    input('user-email').dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    button('Create user').click();
    http.expectOne({ method: 'POST', url: '/api/v1/users' }).flush(
      {
        errors: [{ message: 'The address is an admin of the deployment, it is no user to add.' }],
      },
      { status: 409, statusText: 'Conflict' },
    );
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid=add-error]')?.textContent).toContain(
      'The address is an admin of the deployment',
    );
  });
});
