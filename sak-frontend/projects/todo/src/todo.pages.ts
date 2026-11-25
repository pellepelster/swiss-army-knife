import { Routes } from '@angular/router';
import { LIST_APP, ListApp, ListSettingsForm, ListsPage, pageTitle } from '@sak/core';
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
    empty: 'lists.empty',
    countNone: 'todo.lists.open_none',
    countOne: 'todo.lists.open_one',
    countOther: 'todo.lists.open_other',
    sharedBadge: 'lists.shared_badge',
    settingsOf: 'lists.settings_of',
    listsNotLoaded: 'lists.not_loaded',
    listsForbidden: 'todo.lists_forbidden',
    onlyOwnerShares: 'todo.only_owner_shares',
    onlyOwnerDeletes: 'lists.only_owner_deletes',
    listGone: 'lists.list_gone',
    back: 'lists.back',
    create: 'lists.form.create',
    icon: 'lists.form.icon',
    chooseIcon: 'lists.form.choose_icon',
    noIcon: 'lists.form.no_icon',
    shared: 'lists.form.shared',
    sharedHint: 'todo.form.shared_hint',
    deleteList: 'lists.form.delete_list',
    noSuchList: 'lists.form.no_such_list',
    notLoaded: 'lists.form.not_loaded',
    notSaved: 'lists.form.not_saved',
    notDeleted: 'lists.form.not_deleted',
  },
};

// the pages of the todo app below /todo, loaded with the first of them (todo.routes.ts): all lists
// first (LIST-3), then one list, a page to create a list and one to edit it
export const TODO_PAGES: Routes = [
  {
    path: '',
    providers: [{ provide: LIST_APP, useValue: TODO_LIST_APP }],
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
  },
];
