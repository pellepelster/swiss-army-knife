import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiUrl, apiUrlInterceptor } from './api-url';

describe('apiUrl', () => {
  it('calls api.<root_domain>/v1 from apps.<root_domain>', () => {
    const apps = new URL('https://apps.pelle.io/notes');
    expect(apiUrl('/api/v1/notes?limit=500', apps)).toBe('https://api.pelle.io/v1/notes?limit=500');
    expect(apiUrl('/api/v1/files/7/thumbnail', apps)).toBe(
      'https://api.pelle.io/v1/files/7/thumbnail',
    );
    expect(apiUrl('https://example.org/api/v1/notes', apps)).toBe(
      'https://example.org/api/v1/notes',
    );
    expect(apiUrl('/swagger/openapi.json', apps)).toBe('/swagger/openapi.json');
  });

  it('stays on the same origin everywhere else', () => {
    expect(apiUrl('/api/v1/notes', new URL('http://localhost:4200/notes'))).toBe('/api/v1/notes');
    expect(apiUrl('/api/v1/notes', new URL('http://localhost:18080/'))).toBe('/api/v1/notes');
  });

  it('sends the requests of the api past the service worker, and only them', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiUrlInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const http = TestBed.inject(HttpClient);
    const testing = TestBed.inject(HttpTestingController);

    http.get('/api/v1/notes').subscribe();
    http.get('/swagger/openapi.json').subscribe();
    expect(testing.expectOne('/api/v1/notes').request.headers.get('ngsw-bypass')).toBe('true');
    expect(testing.expectOne('/swagger/openapi.json').request.headers.has('ngsw-bypass')).toBe(
      false,
    );
    testing.verify();
  });
});
