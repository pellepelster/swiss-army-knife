import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { LEAVE_PAGE } from '@sak/core';
import { pageHelpers } from '@sak/testing';
import { UserSettingsPage } from './user-settings-page';

const ai = (value: boolean, available = true) => ({
  name: 'ai',
  label: 'Use AI',
  description: 'The photo is sent to OpenRouter (model vision/model).',
  type: 'boolean',
  default: false,
  value,
  available,
  unavailableReason: available ? null : 'No AI is set up for this SAK instance.',
});

const scopes = (notes: object, grocery: object) => ({
  scopes: [
    { name: 'notes', label: 'Notes', settings: [notes] },
    { name: 'grocery', label: 'Grocery', settings: [grocery] },
  ],
});

describe('UserSettingsPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  // where a sign out leads
  let leftTo: string[];

  beforeEach(async () => {
    leftTo = [];
    TestBed.configureTestingModule({
      providers: [
        { provide: LEAVE_PAGE, useValue: (url: string) => leftTo.push(url) },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'user/settings', component: UserSettingsPage }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open, text } = pageHelpers(() => harness);
  const toggle = (scope: string) =>
    page().querySelector<HTMLInputElement>(`#user-setting-${scope}-ai`)!;

  const load = async (settings: object) => {
    await open('/user/settings');
    http.expectOne('/api/v1/user/settings').flush(settings);
    await harness.fixture.whenStable();
  };

  it('shows a switch for the AI of each app, with where the data goes', async () => {
    await load(scopes(ai(false), ai(true)));

    expect(page().querySelector('h1')?.textContent).toContain('My settings');
    expect(
      [...page().querySelectorAll('[data-testid^=scope-] h2')].map((it) => it.textContent?.trim()),
    ).toEqual(['Notes', 'Grocery']);
    expect(toggle('notes').checked).toBe(false);
    expect(toggle('grocery').checked).toBe(true);
    expect(page().querySelector('[data-testid=scope-notes]')?.textContent).toContain(
      'OpenRouter (model vision/model)',
    );
    expect(button('Save').disabled).toBe(true);
  });

  it('saves only the scopes changed, one Save for all', async () => {
    await load(scopes(ai(false), ai(false)));

    toggle('notes').click();
    await harness.fixture.whenStable();
    expect(button('Save').disabled).toBe(false);
    button('Save').click();
    const request = http.expectOne('/api/v1/user/settings/notes');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ ai: true });
    request.flush({ name: 'notes', label: 'Notes', settings: [ai(true)] });
    await harness.fixture.whenStable();

    expect(text('saved')).toBe('Saved.');
    expect(toggle('notes').checked).toBe(true);
    expect(button('Save').disabled).toBe(true);
  });

  it('a switch back to how it was is no change', async () => {
    await load(scopes(ai(false), ai(false)));

    toggle('grocery').click();
    toggle('grocery').click();
    await harness.fixture.whenStable();
    expect(button('Save').disabled).toBe(true);
  });

  it('without an AI set up it can not be turned on, but turned off, and says why', async () => {
    await load(scopes(ai(false, false), ai(true, false)));

    expect(toggle('notes').disabled).toBe(true);
    expect(toggle('grocery').disabled).toBe(false);
    expect(text('unavailable')).toBe('No AI is set up for this SAK instance.');
  });

  it('says when the settings could not be saved', async () => {
    await load(scopes(ai(false), ai(false)));
    toggle('notes').click();
    await harness.fixture.whenStable();
    button('Save').click();
    http
      .expectOne('/api/v1/user/settings/notes')
      .flush(
        { errors: [{ code: 'openrouter.not_set_up', message: 'No AI is set up.' }] },
        { status: 409, statusText: 'Conflict' },
      );
    await harness.fixture.whenStable();
    expect(text('save-error')).toBe('No AI is set up.');
  });

  it('signs out everywhere after a question in its place, cancel is the way back (SEC-4)', async () => {
    await load(scopes(ai(false), ai(true)));
    const section = page().querySelector('[data-testid=sign-out-everywhere]')!;
    expect(section.textContent).toContain('Signs you out on every device');
    button('Sign out everywhere', section).click();
    await harness.fixture.whenStable();
    expect(section.textContent).toContain('Sign out on every device?');
    button('Cancel', section).click();
    await harness.fixture.whenStable();
    http.expectNone({ method: 'POST' });
    expect(section.textContent).not.toContain('Sign out on every device?');

    button('Sign out everywhere', section).click();
    await harness.fixture.whenStable();
    page().querySelector<HTMLButtonElement>('[data-testid=confirm-sign-out-everywhere]')!.click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/auth/sign-out-everywhere' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(leftTo).toEqual(['/login']);
  });

  it('says when it could not sign out everywhere, and stays (SEC-4)', async () => {
    await load(scopes(ai(false), ai(true)));
    const section = page().querySelector('[data-testid=sign-out-everywhere]')!;
    button('Sign out everywhere', section).click();
    await harness.fixture.whenStable();
    page().querySelector<HTMLButtonElement>('[data-testid=confirm-sign-out-everywhere]')!.click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/auth/sign-out-everywhere' })
      .flush(null, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(section.querySelector('[role=alert]')?.textContent).toContain(
      'You could not be signed out everywhere.',
    );
    expect(leftTo).toEqual([]);
  });

  it('says when the settings could not be loaded', async () => {
    await open('/user/settings');
    http.expectOne('/api/v1/user/settings').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await harness.fixture.whenStable();
    expect(page().textContent).toContain('The settings could not be loaded.');
  });
});
