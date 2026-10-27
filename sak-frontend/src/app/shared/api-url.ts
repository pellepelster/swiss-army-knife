import { HttpInterceptorFn } from '@angular/common/http';

// the api of the apps is /api/v1 of the backend; served at apps.<root_domain>, the frontend calls
// it at api.<root_domain>/v1 instead, which caddy leads there. Everywhere else, the dev server and
// the backend serving the frontend itself, it is on the same origin
const API_PREFIX = '/api/v1/';

export function apiUrl(url: string, here: Location | URL = location): string {
  if (!url.startsWith(API_PREFIX) || !here.hostname.startsWith('apps.')) return url;
  const host = here.host.replace(/^apps\./, 'api.');
  return `${here.protocol}//${host}/v1/${url.slice(API_PREFIX.length)}`;
}

// every request of the api to where apiUrl says
export const apiUrlInterceptor: HttpInterceptorFn = (request, next) => {
  const url = apiUrl(request.url);
  return next(url === request.url ? request : request.clone({ url }));
};
