import { Injectable } from '@angular/core';
import { ListChanges } from '@sak/core';

export { CHANGES_DEBOUNCE_MS, EVERYTHING } from '@sak/core';

// the changes of the grocery lists the user sees (LIST-4)
@Injectable({ providedIn: 'root' })
export class GroceryChanges extends ListChanges {
  protected readonly path = '/api/v1/grocery/changes';
}
