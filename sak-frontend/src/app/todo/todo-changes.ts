import { Injectable } from '@angular/core';
import { ListChanges } from '../shared/list-changes';

export { CHANGES_DEBOUNCE_MS, EVERYTHING } from '../shared/list-changes';

// the changes of the todo lists the user sees (LIST-4)
@Injectable({ providedIn: 'root' })
export class TodoChanges extends ListChanges {
  protected readonly path = '/api/v1/todo/changes';
}
