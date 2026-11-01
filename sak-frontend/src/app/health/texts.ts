import { texts } from '../i18n/texts';

// the texts of health (health.*)
export const HEALTH = texts(
  {
    title: 'Health',
    all_apps: 'All apps',
    check_again: 'Check again',
    checks: 'Health checks',
    none: 'There are no health checks.',
    checking: 'Checking…',
    state: {
      healthy: 'Healthy',
      unhealthy: 'Unhealthy',
      unknown: 'Unknown',
    },
    not_checked: 'The health could not be checked.',
    forbidden_see: 'Your role does not allow to see the health.',
  },
  {
    title: 'Zustand',
    all_apps: 'Alle Apps',
    check_again: 'Erneut prüfen',
    checks: 'Zustandsprüfungen',
    none: 'Es gibt keine Zustandsprüfungen.',
    checking: 'Wird geprüft…',
    state: {
      healthy: 'Gesund',
      unhealthy: 'Nicht gesund',
      unknown: 'Unbekannt',
    },
    not_checked: 'Der Zustand konnte nicht geprüft werden.',
    forbidden_see: 'Deine Rolle erlaubt nicht, den Zustand zu sehen.',
  },
);
