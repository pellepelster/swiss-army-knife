import { InjectionToken, ProviderToken } from '@angular/core';
import { TextKey } from '../i18n/i18n';
import { ListChanges } from './list-changes';
import { ListsService, SharedList } from './list-service';

// what the pages shared by the list-based apps (ListsPage, ListSettingsForm, OpenList) need of an
// app; its routes provide it as LIST_APP
export interface ListApp {
  // the start of the app, all lists, e.g. /todo; a list is at <route>/lists/<id>
  readonly route: string;
  readonly service: ProviderToken<ListsService<SharedList>>;
  readonly changes: ProviderToken<ListChanges>;
  // the texts of the app, the same meaning in every app
  readonly texts: {
    // the name of the app, the title of all lists
    readonly title: TextKey;
    // the label of all lists, e.g. Todo lists
    readonly lists: TextKey;
    readonly empty: TextKey;
    // how many items of a list are open: none, one and %{count}
    readonly countNone: TextKey;
    readonly countOne: TextKey;
    readonly countOther: TextKey;
    readonly sharedBadge: TextKey;
    // Settings of %{name}
    readonly settingsOf: TextKey;
    readonly listsNotLoaded: TextKey;
    readonly listsForbidden: TextKey;
    readonly onlyOwnerShares: TextKey;
    readonly onlyOwnerDeletes: TextKey;
    // the list was unshared or deleted while its settings were open
    readonly listGone: TextKey;
    readonly back: TextKey;
    readonly create: TextKey;
    readonly icon: TextKey;
    readonly chooseIcon: TextKey;
    readonly noIcon: TextKey;
    readonly shared: TextKey;
    readonly sharedHint: TextKey;
    readonly deleteList: TextKey;
    readonly noSuchList: TextKey;
    readonly notLoaded: TextKey;
    readonly notSaved: TextKey;
    readonly notDeleted: TextKey;
  };
}

export const LIST_APP = new InjectionToken<ListApp>('LIST_APP');
