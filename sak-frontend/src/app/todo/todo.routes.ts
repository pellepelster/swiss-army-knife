import { Route } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { pageTitle } from '../i18n/i18n';
import { AppDownload } from '../shared/app-download';
import { LIST_APP, ListApp } from '../shared/list-app';
import { ListSettingsForm } from '../shared/list-settings-form';
import { ListsPage } from '../shared/lists-page';
import { TodoChanges } from './todo-changes';
import { TodoPage } from './todo-page';
import { TodoService } from './todo.service';

// the todo app for the pages all list-based apps share
export const TODO_LIST_APP: ListApp = {
  route: '/todo',
  service: TodoService,
  changes: TodoChanges,
  texts: {
    title: 'app.todo',
    lists: 'todo.lists.label',
    empty: 'todo.lists.empty',
    countNone: 'todo.lists.open_none',
    countOne: 'todo.lists.open_one',
    countOther: 'todo.lists.open_other',
    sharedBadge: 'todo.shared_badge',
    settingsOf: 'todo.settings_of',
    listsNotLoaded: 'todo.lists_not_loaded',
    listsForbidden: 'todo.lists_forbidden',
    onlyOwnerShares: 'todo.only_owner_shares',
    onlyOwnerDeletes: 'todo.only_owner_deletes',
    listGone: 'todo.list_gone',
    back: 'todo.back',
    create: 'todo.form.create',
    icon: 'todo.form.icon',
    chooseIcon: 'todo.form.choose_icon',
    noIcon: 'todo.form.no_icon',
    shared: 'todo.form.shared',
    sharedHint: 'todo.form.shared_hint',
    deleteList: 'todo.form.delete_list',
    noSuchList: 'todo.form.no_such_list',
    notLoaded: 'todo.form.not_loaded',
    notSaved: 'todo.form.not_saved',
    notDeleted: 'todo.form.not_deleted',
  },
};

// the android app of the todo lists to download, without a session; before todoRoute, whose login
// guard would take it otherwise
export const todoDownloadRoute: Route = {
  path: 'todo/download/app',
  component: AppDownload,
  title: pageTitle('todo.download.page_title'),
  data: { app: 'todo', favicon: { svg: 'icons/todo.svg', png: 'icons/todo-192.png' } },
};

// the todo app below /todo, behind the login: all lists first (LIST-3), then one list, a page to
// create a list and one to edit it. It installs as an app of its own and has its own favicon
export const todoRoute: Route = {
  path: 'todo',
  canActivate: [authGuard],
  providers: [{ provide: LIST_APP, useValue: TODO_LIST_APP }],
  data: {
    favicon: { svg: 'icons/todo.svg', png: 'icons/todo-192.png' },
    app: { manifest: 'todo.webmanifest', appleTouchIcon: 'icons/todo-apple-touch-180.png' },
  },
  children: [
    { path: '', component: ListsPage, title: pageTitle('app.todo') },
    {
      path: 'lists/create',
      component: ListSettingsForm,
      title: pageTitle('todo.page_title.create'),
    },
    { path: 'lists/:id', component: TodoPage, title: pageTitle('todo.page_title.list') },
    {
      path: 'lists/:id/edit',
      component: ListSettingsForm,
      title: pageTitle('todo.page_title.settings'),
    },
  ],
};
