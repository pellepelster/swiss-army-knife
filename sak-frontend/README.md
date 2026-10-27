# sak-frontend

The apps (notes, static hosting, redirects), an Angular app the backend serves; `mise run local:run`
runs it with the backend on http://localhost:4200.

## Service worker

The production build installs Angular's service worker (`ngsw-config.json`), the apps start
without the network. The requests of the api go past it (`ngsw-bypass`, `shared/api-url.ts`).
`ServiceWorkerUpdates` reloads the page when the worker can no longer serve the version it runs,
takes a new version over at the next navigation and looks for one whenever the app comes back
into view.

If a worker ever breaks for everyone, either of these takes it out of every browser at its next
visit, without anybody unregistering it by hand:

- answer `/ngsw.json` with 404: the worker finds no manifest, clears its caches and unregisters
  itself;
- serve `node_modules/@angular/service-worker/safety-worker.js` as `/ngsw-worker.js`: it
  unregisters the worker.
