import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { pageHelpers, pageOf } from '../testing/page';
import { SettingsPage } from './settings-page';
import { SettingsScopeTab } from './settings-scope-tab';
import { SettingsStore } from './settings-store';

const system = {
  name: 'system',
  label: 'System',
  description: 'The settings of the whole deployment.',
  enabledBy: null,
  settings: [
    {
      name: 'ai',
      label: 'AI features',
      description: 'Lets the apps send data to OpenRouter.',
      type: 'boolean',
      default: false,
      maxLength: null,
      mask: null,
      readOnly: false,
      requires: null,
    },
    {
      name: 'greeting',
      label: 'Greeting',
      description: 'Says hello.',
      type: 'text',
      default: 'hello',
      maxLength: 10,
      mask: null,
      readOnly: false,
      requires: null,
    },
    {
      name: 'key',
      label: 'Key',
      description: 'Opens something.',
      type: 'text',
      default: '',
      maxLength: 255,
      mask: 2,
      readOnly: false,
      requires: null,
    },
    {
      name: 'model',
      label: 'Model',
      description: 'Set in the configuration.',
      type: 'text',
      default: '',
      maxLength: 255,
      mask: null,
      readOnly: true,
      requires: null,
    },
  ],
};

const other = { ...system, name: 'other', label: 'Other', settings: [system.settings[0]] };

describe('SettingsPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            {
              path: 'settings',
              component: SettingsPage,
              children: [{ path: ':scope', component: SettingsScopeTab }],
            },
          ],
          withComponentInputBinding(),
        ),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  const { page, button, open, url } = pageHelpers(() => harness);
  const input = (id: string) => page().querySelector<HTMLInputElement>(`#${id}`)!;
  const tabs = () =>
    [...page().querySelectorAll<HTMLAnchorElement>('nav[aria-label=Scopes] a')].map((tab) => [
      tab.textContent?.trim(),
      tab.classList.contains('active'),
    ]);

  // the page with the scopes, the first or the one of the url opened with its values
  const load = async (values: object, scopes: object[] = [system, other], path = '/settings') => {
    await open(path);
    http.expectOne('/api/v1/settings?offset=0&limit=100').flush(pageOf(scopes));
    await harness.fixture.whenStable();
    http.expectOne(`/api/v1/settings/${url().split('/')[2]}`).flush(values);
    await harness.fixture.whenStable();
  };

  it('shows a tab for every scope and opens the first', async () => {
    await load({ ai: true, greeting: 'hello', key: 'se••••••••', model: 'vision/model' });

    expect(url()).toBe('/settings/system');
    expect(tabs()).toEqual([
      ['System', true],
      ['Other', false],
    ]);
    const ai = input('setting-system-ai');
    expect(ai.type).toBe('checkbox');
    expect(ai.checked).toBe(true);
    const greeting = input('setting-system-greeting');
    expect(greeting.value).toBe('hello');
    expect(greeting.getAttribute('maxlength')).toBe('10');
    // a masked value is its placeholder, the field itself is empty
    const key = input('setting-system-key');
    expect(key.value).toBe('');
    expect(key.placeholder).toBe('se••••••••');
    // a value of the backend, shown but not changed
    const model = input('setting-system-model');
    expect(model.value).toBe('vision/model');
    expect(model.readOnly).toBe(true);
    expect(model.classList).toContain('form-control-plaintext');
    expect(button('Save').disabled).toBe(true);
  });

  it('opens the scope of the url, another tab loads its own', async () => {
    await load({ ai: false }, [system, other], '/settings/other');
    expect(tabs()).toEqual([
      ['System', false],
      ['Other', true],
    ]);

    button('System').click();
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/settings/system').flush({ ai: false, greeting: 'hi', key: '' });
    await harness.fixture.whenStable();
    expect(input('setting-system-greeting').value).toBe('hi');
  });

  it('greys out the settings of a scope while its switch is off, turning it on enables them', async () => {
    await load({ ai: false, greeting: 'hello', key: '' }, [{ ...system, enabledBy: 'ai' }]);

    expect(input('setting-system-ai').disabled).toBe(false);
    expect(input('setting-system-greeting').disabled).toBe(true);
    expect(input('setting-system-key').disabled).toBe(true);

    input('setting-system-ai').click();
    await harness.fixture.whenStable();
    expect(input('setting-system-greeting').disabled).toBe(false);
  });

  it('greys out a setting whose requirement in another scope is off, with its stored value', async () => {
    const ai = {
      name: 'ai',
      label: 'AI',
      description: 'The AI.',
      enabledBy: 'enabled',
      settings: [{ ...system.settings[0], name: 'enabled', label: 'AI features' }],
    };
    const notes = {
      name: 'notes',
      label: 'Notes',
      description: 'The notes.',
      enabledBy: null,
      settings: [
        {
          ...system.settings[0],
          label: 'AI features',
          requires: { scope: 'ai', setting: 'enabled' },
        },
      ],
    };
    await open('/settings/notes');
    http.expectOne('/api/v1/settings?offset=0&limit=100').flush(pageOf([ai, notes]));
    await harness.fixture.whenStable();
    http.expectOne('/api/v1/settings/notes').flush({ ai: true });
    http.expectOne('/api/v1/settings/ai').flush({ enabled: false });
    await harness.fixture.whenStable();

    const dependent = input('setting-notes-ai');
    expect(dependent.checked).toBe(true);
    expect(dependent.disabled).toBe(true);
    const requires = page().querySelector('[data-testid=requires]')!;
    expect(requires.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Needs AI features of AI turned on.',
    );
    expect(requires.querySelector('a')?.getAttribute('href')).toBe('/settings/ai');

    // the required scope turned on, e.g. saved on its tab
    TestBed.inject(SettingsStore).values.update((values) => ({ ...values, ai: { enabled: true } }));
    await harness.fixture.whenStable();
    expect(input('setting-notes-ai').disabled).toBe(false);
    expect(page().querySelector('[data-testid=requires]')).toBeNull();
  });

  it('saves the values changed of its scope only and takes what the backend answers', async () => {
    await load({ ai: false, greeting: 'hello', key: '' });

    input('setting-system-ai').click();
    await harness.fixture.whenStable();
    button('Save').click();

    const request = http.expectOne({ method: 'PUT', url: '/api/v1/settings/system' });
    expect(request.request.body).toEqual({ ai: true });
    request.flush({ ai: true, greeting: 'hello', key: '' });
    await harness.fixture.whenStable();

    expect(input('setting-system-ai').checked).toBe(true);
    expect(button('Save').disabled).toBe(true);
  });

  it('shows what the backend did not take, the change stays', async () => {
    await load({ ai: false, greeting: 'hello', key: '' });

    const greeting = input('setting-system-greeting');
    greeting.value = 'hi there';
    greeting.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    button('Save').click();
    http
      .expectOne({ method: 'PUT', url: '/api/v1/settings/system' })
      .flush(
        { errors: [{ message: '"Greeting" must be at most 10 characters.' }] },
        { status: 400, statusText: 'Bad Request' },
      );
    await harness.fixture.whenStable();

    expect(page().querySelector('[data-testid=save-error]')?.textContent?.trim()).toBe(
      '"Greeting" must be at most 10 characters.',
    );
    expect(input('setting-system-greeting').value).toBe('hi there');
  });
});
