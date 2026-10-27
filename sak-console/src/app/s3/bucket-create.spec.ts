import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { BucketCreate } from './bucket-create';

const CREATED = '2026-10-27T07:00:00Z';

@Component({ template: '' })
class Elsewhere {}

describe('BucketCreate', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 's3/buckets', component: Elsewhere },
          { path: 's3/buckets/create', component: BucketCreate },
          { path: 's3/buckets/:name', component: Elsewhere },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const open = async () => {
    const harness = await RouterTestingHarness.create('/s3/buckets/create');
    const page = harness.routeNativeElement!;
    const type = async (selector: string, value: string) => {
      const input = page.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await harness.fixture.whenStable();
    };
    const button = (text: string) =>
      [...page.querySelectorAll<HTMLElement>('button, a')].find(
        (b) => b.textContent?.trim() === text,
      )! as HTMLButtonElement;
    return { harness, page, type, button };
  };

  it('creates a bucket with its domains and opens it', async () => {
    const { harness, page, type, button } = await open();
    expect(page.querySelector('[role=dialog]')).toBeNull();

    await type('#bucket-name', 'No_Bucket');
    expect(button('Add bucket').disabled).toBe(true);
    await type('#bucket-name', 'new-bucket');
    expect(button('Add bucket').disabled).toBe(false);

    await type('#domain-0', 'no domain');
    expect(button('Add bucket').disabled).toBe(true);
    await type('#domain-0', 'example.org');
    button('Add domain').click();
    await harness.fixture.whenStable();
    await type('#domain-1', 'www.example.org');
    button('Add domain').click();
    await harness.fixture.whenStable();
    expect(button('Add bucket').disabled).toBe(false);
    button('Add bucket').click();

    const request = http.expectOne({ method: 'POST', url: '/api/v1/buckets' });
    // the blank third domain is left out
    expect(request.request.body).toEqual({ name: 'new-bucket', domains: ['example.org', 'www.example.org'] });
    request.flush(
      {
        name: 'new-bucket',
        domains: ['example.org', 'www.example.org'],
        createdAt: CREATED,
        statistics: { computing: true, failed: false },
      },
      { status: 201, statusText: 'Created' },
    );
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/s3/buckets/new-bucket');
  });

  it('shows what the api rejected the bucket for and stays', async () => {
    const { harness, page, type, button } = await open();
    await type('#bucket-name', 'backups');
    page.querySelector<HTMLButtonElement>('[aria-label="Remove domain 1"]')!.click();
    await harness.fixture.whenStable();
    button('Add bucket').click();
    http
      .expectOne({ method: 'POST', url: '/api/v1/buckets' })
      .flush({ errors: [{ message: 'A bucket of the name exists.' }] }, { status: 409, statusText: 'Conflict' });
    await harness.fixture.whenStable();

    expect(page.querySelector('[data-testid=create-error]')?.textContent?.trim()).toBe('A bucket of the name exists.');
    expect(TestBed.inject(Router).url).toBe('/s3/buckets/create');
  });

  it('goes back to the buckets on cancel', async () => {
    const { harness, button } = await open();
    button('Cancel').click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/s3/buckets');
  });
});
