import { Injectable } from '@angular/core';
import { ListChanges } from '@sak/core';

export { CHANGES_DEBOUNCE_MS, EVERYTHING } from '@sak/core';

// the changes of the todo lists the user sees (LIST-4)
@Injectable({ providedIn: 'root' })
export class TodoChanges extends ListChanges {
  protected readonly path = '/api/v1/todo/changes';
}
