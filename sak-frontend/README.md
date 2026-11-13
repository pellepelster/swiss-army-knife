# sak-frontend

The web apps (notes, grocery lists, todo lists, static hosting, redirects), an Angular app that the backend serves.
`mise run local:run` runs it with the backend on http://localhost:4200.

## Service worker

The production build installs the service worker of Angular (`ngsw-config.json`), so the apps start without the
network. The api requests go past it (`ngsw-bypass`, `shared/api-url.ts`). `ServiceWorkerUpdates` reloads the page
when the worker cannot serve the running version, and looks for a new version when the app comes back into view.

If a worker breaks for everyone, one of these removes it from every browser at the next visit:

- answer `/ngsw.json` with 404: the worker finds no manifest, clears its caches and unregisters;
- serve `node_modules/@angular/service-worker/safety-worker.js` as `/ngsw-worker.js`.
