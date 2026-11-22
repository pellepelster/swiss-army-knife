// what the apps and the shell use of core. The page of the download of an android app and the
// english texts are entries of their own (@sak/core/app-download, @sak/core/catalog.en in
// tsconfig.json): the page is loaded with its route, the texts only with their language
export type * from './api/schema';
export * from './api/models';
export * from './auth/auth.guard';
export * from './auth/auth.interceptor';
export * from './auth/auth.service';
export * from './favicon.service';
export * from './i18n/i18n';
export * from './i18n/texts';
export * from './service-worker-updates';
export * from './shared/action-menu';
export * from './shared/api';
export * from './shared/api-url';
export * from './shared/confirm-delete';
export * from './shared/copy-button';
export * from './shared/domains';
export * from './shared/email';
export * from './shared/format';
export * from './shared/keepalive';
export * from './shared/list-app';
export * from './shared/list-changes';
export * from './shared/list-icon';
export * from './shared/list-service';
export * from './shared/list-settings-form';
export * from './shared/lists-page';
export * from './shared/local-date-time.pipe';
export * from './shared/open-list';
export * from './shared/page-header';
export * from './shared/paged-list';
export * from './shared/pagination';
export * from './shared/pending-undo';
export * from './shared/photo-scaler';
export * from './shared/row-error';
export * from './shared/scaled-size';
export * from './shared/undo-toast';
