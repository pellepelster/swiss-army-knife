import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService, SESSION_STORAGE_KEY } from '../auth/auth.service';
import { SideBar, initialsOf } from './side-bar';

describe('SideBar', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SideBar>;
  let element: HTMLElement;

  beforeEach(async () => {
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        token: 'session-token',
        email: 'pelle@pelle.io',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        roles: ['admin'],
      }),
    );
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(SideBar);
    await fixture.whenStable();
    element = fixture.nativeElement;
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  const openMenu = async () => {
    element.querySelector<HTMLButtonElement>('[data-testid=personal-menu]')!.click();
    await fixture.whenStable();
    return element.querySelector('[role=menu]');
  };

  it('links the overview and the log views', () => {
    const links = [...element.querySelectorAll<HTMLAnchorElement>('nav a')].map((link) => [
      link.textContent?.trim(),
      link.getAttribute('href'),
    ]);
    expect(links).toEqual([
      ['Overview', '/'],
      ['Statistics', '/logs/statistics'],
      ['Live', '/logs/live'],
      ['Buckets', '/s3'],
      ['Redirects', '/ingress/redirects'],
      ['Swagger', '/swagger/'],
    ]);
  });

  it('signs out from the personal menu at the bottom', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const chip = element.querySelector('[data-testid=personal-menu]')!;
    expect(chip.textContent).toContain('pelle@pelle.io');
    expect(chip.querySelector('.avatar')?.textContent).toBe('P');
    expect(element.querySelector('[role=menu]')).toBeNull();

    const menu = (await openMenu())!;
    expect(menu.textContent).toContain('Signed in as');
    expect(menu.textContent).toContain('Session expires');
    expect(menu.querySelector('[data-testid=roles]')?.textContent).toBe('admin');

    menu.querySelector<HTMLButtonElement>('[role=menuitem]')!.click();

    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('closes the personal menu on a click elsewhere', async () => {
    expect(await openMenu()).not.toBeNull();

    document.body.click();
    await fixture.whenStable();

    expect(element.querySelector('[role=menu]')).toBeNull();
  });
});

describe('initialsOf', () => {
  it('takes the initials of the local part', () => {
    expect(initialsOf('pelle@pelle.io')).toBe('P');
    expect(initialsOf('christian.pelster@pelle.io')).toBe('CP');
    expect(initialsOf(null)).toBe('?');
  });
});
