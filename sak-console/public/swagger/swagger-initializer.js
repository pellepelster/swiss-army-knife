// swagger ui of the console api, served next to the console so it can use the console's session
// for "try it out"; a file instead of an inline script, the content security policy forbids those
window.onload = function () {
  const ui = SwaggerUIBundle({
    url: 'openapi.json',
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
