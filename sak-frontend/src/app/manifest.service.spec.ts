import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { DEFAULT_APP, ManifestService } from './manifest.service';

@Component({ template: '' })
class Page {}

describe('ManifestService', () => {
  const href = (rel: string) =>
    document.head.querySelector(`link[rel="${rel}"]`)?.getAttribute('href');

  beforeEach(() => {
    document.head
      .querySelectorAll('link[rel="manifest"], link[rel="apple-touch-icon"]')
      .forEach((link) => link.remove());
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: Page },
          {
            path: 'grocery',
            data: {
              app: {
                manifest: 'grocery.webmanifest',
                appleTouchIcon: 'icons/grocery-apple-touch-180.png',
              },
            },
            children: [{ path: 'lists/:id', component: Page }],
          },
        ]),
      ],
    });
    TestBed.inject(ManifestService);
  });

  it('links the manifest of the app of the route, the notes where a route names none', async () => {
    const harness = await RouterTestingHarness.create('/grocery/lists/1');
    expect(href('manifest')).toBe('grocery.webmanifest');
    expect(href('apple-touch-icon')).toBe('icons/grocery-apple-touch-180.png');

    await harness.navigateByUrl('/');
    expect(href('manifest')).toBe(DEFAULT_APP.manifest);
    expect(href('apple-touch-icon')).toBe(DEFAULT_APP.appleTouchIcon);
    expect(document.head.querySelectorAll('link[rel="manifest"]').length).toBe(1);
  });
});
