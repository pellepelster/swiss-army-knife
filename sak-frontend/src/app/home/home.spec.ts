import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.routes';

describe('Home', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter(routes)] }));

  it('lists the apps and opens the notes at /notes', async () => {
    const harness = await RouterTestingHarness.create('/');
    const links = [...harness.routeNativeElement!.querySelectorAll<HTMLAnchorElement>('nav a')];
    expect(links.map((link) => link.querySelector('.name')?.textContent)).toEqual(['Notes']);
    expect(links[0].getAttribute('href')).toBe('/notes');

    links[0].click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/notes');
    expect(harness.routeNativeElement!.querySelector('h1')?.textContent).toBe('Notes');
  });

  it('leads unknown paths to the apps', async () => {
    await RouterTestingHarness.create('/unknown');
    expect(TestBed.inject(Router).url).toBe('/');
  });
});
