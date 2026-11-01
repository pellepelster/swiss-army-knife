import { texts } from '../i18n/texts';

// the texts of settings (settings.*)
export const SETTINGS = texts(
  {
    title: 'Settings',
    all_apps: 'All apps',
    scopes: 'Scopes',
    loading: 'Loading…',
    save: 'Save',
    needs_before: 'Needs',
    needs_after: 'turned on.',
    required_label: '%{setting} of %{scope}',
    no_scope: 'There is no such scope of settings.',
    not_loaded: 'The settings could not be loaded.',
    not_saved: 'The settings could not be saved.',
    forbidden_see: 'Your role does not allow to see the settings.',
    forbidden_change: 'Your role does not allow to change the settings.',
  },
  {
    title: 'Einstellungen',
    all_apps: 'Alle Apps',
    scopes: 'Bereiche',
    loading: 'Wird geladen…',
    save: 'Speichern',
    needs_before: 'Dafür muss',
    needs_after: 'eingeschaltet sein.',
    required_label: '%{setting} in %{scope}',
    no_scope: 'Diesen Bereich der Einstellungen gibt es nicht.',
    not_loaded: 'Die Einstellungen konnten nicht geladen werden.',
    not_saved: 'Die Einstellungen konnten nicht gespeichert werden.',
    forbidden_see: 'Deine Rolle erlaubt nicht, die Einstellungen zu sehen.',
    forbidden_change: 'Deine Rolle erlaubt nicht, die Einstellungen zu ändern.',
  },
);
