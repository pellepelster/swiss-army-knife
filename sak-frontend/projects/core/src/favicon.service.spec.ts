import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { DEFAULT_FAVICON, FaviconService } from './favicon.service';

@Component({ template: '' })
class Page {}

describe('FaviconService', () => {
  const href = (type: string) =>
    document.head.querySelector(`link[rel="icon"][type="${type}"]`)?.getAttribute('href');

  beforeEach(() => {
    document.head.querySelectorAll('link[rel="icon"]').forEach((link) => link.remove());
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: Page },
          {
            path: 'notes',
            component: Page,
            data: { favicon: { svg: 'icons/notes.svg', png: 'icons/notes-192.png' } },
          },
        ]),
      ],
    });
    TestBed.inject(FaviconService);
  });

  it('shows the favicon of the route and the knife where a route names none', async () => {
    const harness = await RouterTestingHarness.create('/notes');
    expect(href('image/svg+xml')).toBe('icons/notes.svg');
    expect(href('image/png')).toBe('icons/notes-192.png');

    await harness.navigateByUrl('/');
    expect(href('image/svg+xml')).toBe(DEFAULT_FAVICON.svg);
    expect(href('image/png')).toBe(DEFAULT_FAVICON.png);
    expect(document.head.querySelectorAll('link[rel="icon"]').length).toBe(2);
  });
});
