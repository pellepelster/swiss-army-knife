// swagger ui of the api of the apps, served next to them so it can use their session for
// "try it out"; a file instead of an inline script, the content security policy forbids those.
// At sak.<base_domain> the api is api.<base_domain>/v1, like the apps call it (shared/api-url.ts)
function apiUrl(url) {
  const here = new URL(url, location.href);
  if (here.origin !== location.origin || !here.pathname.startsWith('/api/v1/')) return url;
  if (!location.hostname.startsWith('sak.')) return url;
  const host = location.host.replace(/^sak\./, 'api.');
  return `${location.protocol}//${host}/v1/${here.pathname.slice('/api/v1/'.length)}${here.search}`;
}

window.onload = function () {
  const ui = SwaggerUIBundle({
    url: 'openapi.json',
    requestInterceptor: function (request) {
      request.url = apiUrl(request.url);
      return request;
    },
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis],
    layout: 'BaseLayout',
    onComplete: function () {
      try {
        const session = JSON.parse(localStorage.getItem('sak.session') || 'null');
        if (session && session.token && Date.parse(session.expiresAt) > Date.now()) {
          ui.preauthorizeApiKey('session', session.token);
        }
      } catch (e) {
        // signed out or no storage, the token can still be entered with "authorize"
      }
    },
  });
  window.ui = ui;
};
