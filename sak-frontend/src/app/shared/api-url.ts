import { HttpInterceptorFn } from '@angular/common/http';

// the api of the apps is /api/v1 of the backend; served at apps.<root_domain>, the frontend calls
// it at api.<root_domain>/v1 instead, which caddy leads there. Everywhere else, the dev server and
// the backend serving the frontend itself, it is on the same origin
const API_PREFIX = '/api/v1/';

// the host of the api for the host of the apps (with or without its port), e.g. api.example.org
// for apps.example.org; swagger-initializer.js, outside of angular, does the same
export function apiHost(appsHost: string): string {
  return `api.${appsHost.replace(/^apps\./, '')}`;
}

export function apiUrl(url: string, here: Location | URL = location): string {
  if (!url.startsWith(API_PREFIX) || !here.hostname.startsWith('apps.')) return url;
  return `${here.protocol}//${apiHost(here.host)}/v1/${url.slice(API_PREFIX.length)}`;
}

// the service worker keeps nothing of the api: its requests go past it to the network, their
// answers and failures show as they are instead of the worker's 504; the backend allows the
// header in its cors configuration
export const SERVICE_WORKER_BYPASS = { 'ngsw-bypass': 'true' };

// every request of the api to where apiUrl says, past the service worker
export const apiUrlInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(API_PREFIX)) return next(request);
  return next(request.clone({ url: apiUrl(request.url), setHeaders: SERVICE_WORKER_BYPASS }));
};
