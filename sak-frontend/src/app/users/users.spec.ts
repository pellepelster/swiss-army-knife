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

  const { page, button, open, url } = pageHelpers(() => harness);
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
    expect(button('Delete admin@example.org').disabled).toBe(true);
    expect(button('Add user').getAttribute('href')).toBe('/users/create');
  });

  it('deletes a user after a confirmation, keep is the way back', async () => {
    await list([admin, anna]);
    button('Delete anna@example.org').click();
    await harness.fixture.whenStable();
    expect(page().textContent).toContain(
      'Delete Anna? They can no longer sign in; their data stays.',
    );
    button('Keep').click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'DELETE' });

    button('Delete anna@example.org').click();
    await harness.fixture.whenStable();
    button('Delete', page().querySelector('[aria-label="Delete the user"]')!).click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/users/3' }).flush(null);
    http.expectOne('/api/v1/users?offset=0&limit=50').flush(pageOf([admin]));
    await harness.fixture.whenStable();
    expect(page().querySelector('[data-testid="user-anna@example.org"]')).toBeNull();
  });

  it('adds a user by address with an optional name, the address checked when it is left', async () => {
    await open('/users/create');
    const type = async (id: string, value: string) => {
      input(id).value = value;
      input(id).dispatchEvent(new Event('input'));
      await harness.fixture.whenStable();
    };
    await type('user-email', 'anna');
    // no error while typing
    expect(input('user-email').classList.contains('is-invalid')).toBe(false);
    input('user-email').dispatchEvent(new Event('blur'));
    await harness.fixture.whenStable();
    expect(input('user-email').classList.contains('is-invalid')).toBe(true);
    expect(button('Add user').disabled).toBe(true);

    await type('user-email', ' anna@example.org ');
    expect(input('user-email').classList.contains('is-invalid')).toBe(false);
    await type('user-name', ' Anna ');
    button('Add user').click();
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
    button('Add user').click();
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
