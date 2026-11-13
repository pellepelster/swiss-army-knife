import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import {
  GroceryCategory,
  GroceryItem,
  GroceryList,
  GroceryNewItem,
  GrocerySuggestion,
} from '../api/models';
import { components } from '../api/schema';
import { fetchAll, Page } from '../shared/api';
import { ListsService } from '../shared/list-service';

// the shapes of the api (GROCERY-13: the categories in the order the list shows them; checkedAt
// is missing while an item is to buy, GROCERY-3)
export type {
  GroceryCategory,
  GroceryItem,
  GroceryList,
  GroceryNewItem,
  GrocerySuggestion,
} from '../api/models';

// what is given of an item: the request of a new one without its list
export type GroceryItemValues = Omit<GroceryNewItem, 'listId'>;

// whether the items in a photo can be discovered now, and the items discovered in one, with their
// quantity if the photo has it (GROCERY-16)
export type GrocerySettings = components['schemas']['GrocerySettingsResponse'];
export type GroceryDiscoveredItem = components['schemas']['GroceryDiscoveredItem'];

// the grocery lists of the signed in user and the shared ones, their items and the suggestions
// how many suggestions the field of a new item shows at most
const SUGGESTIONS = 10;

@Injectable({ providedIn: 'root' })
export class GroceryService extends ListsService<GroceryList> {
  protected readonly base = '/api/v1/grocery';

  // the items to buy by their category; with checked also the bought ones, after the ones to buy of
  // their category, the last bought first
  items(listId: number, checked = false): Observable<GroceryItem[]> {
    return fetchAll<GroceryItem>(
      this.http,
      `/api/v1/grocery/lists/${listId}/items`,
      checked ? { checked: 'true' } : {},
    );
  }

  addItem(listId: number, values: GroceryItemValues): Observable<GroceryItem> {
    return this.http.post<GroceryItem>('/api/v1/grocery/items', { listId, ...values });
  }

  updateItem(id: number, values: GroceryItemValues): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}`, values);
  }

  // one of the categories of its list, null for none; also of the next item of its name on the list
  setCategory(id: number, categoryId: number | null): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}/category`, { categoryId });
  }

  // checks the item: it is bought, it goes from the list and stays as its history (GROCERY-3)
  checkItem(id: number): Observable<void> {
    return this.http.post<void>(`/api/v1/grocery/items/${id}/check`, null);
  }

  // removes the item for good, also from the suggestions
  deleteItem(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/grocery/items/${id}`);
  }

  // a photo into the file storage, scaled down already; the id of its file
  uploadPhoto(photo: Blob): Observable<{ id: number }> {
    return this.http.post<{ id: number }>('/api/v1/files', photo, {
      headers: { 'Content-Type': photo.type || 'application/octet-stream' },
    });
  }

  // the photo of the item, also of the next item of its name on the list (GROCERY-12)
  attachPhoto(id: number, fileId: number): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}/photo`, { fileId });
  }

  removePhoto(id: number): Observable<GroceryItem> {
    return this.http.delete<GroceryItem>(`/api/v1/grocery/items/${id}/photo`);
  }

  // whether the robot offers to discover the items in a photo: the AI features turned on
  // (GROCERY-16)
  settings(): Observable<GrocerySettings> {
    return this.http.get<GrocerySettings>('/api/v1/grocery/settings');
  }

  // the items the AI reads in a photo of the file storage; nothing is added yet (GROCERY-16)
  discover(fileId: number): Observable<GroceryDiscoveredItem[]> {
    return this.http
      .post<components['schemas']['GroceryDiscoveredResponse']>(
        `/api/v1/grocery/discover/${fileId}`,
        null,
      )
      .pipe(map((discovered) => discovered.items));
  }

  // the categories of a list in their order (GROCERY-13); only its owner changes them
  categories(listId: number): Observable<GroceryCategory[]> {
    return fetchAll<GroceryCategory>(this.http, `/api/v1/grocery/lists/${listId}/categories`);
  }

  // the last category of the list
  createCategory(listId: number, name: string, icon: string | null): Observable<GroceryCategory> {
    return this.http.post<GroceryCategory>(`/api/v1/grocery/lists/${listId}/categories`, {
      name,
      icon,
    });
  }

  updateCategory(
    listId: number,
    id: number,
    name: string,
    icon: string | null,
  ): Observable<GroceryCategory> {
    return this.http.put<GroceryCategory>(`/api/v1/grocery/lists/${listId}/categories/${id}`, {
      name,
      icon,
    });
  }

  // its items and the suggestions of the list lose it
  deleteCategory(listId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/grocery/lists/${listId}/categories/${id}`);
  }

  // the ids of all categories of the list, each once, in their new order; all of them in it
  orderCategories(listId: number, ids: number[]): Observable<GroceryCategory[]> {
    return this.http
      .put<Page<GroceryCategory>>(`/api/v1/grocery/lists/${listId}/categories/order`, { ids })
      .pipe(map((page) => page.items));
  }

  // the items added to the list before whose name contains the text, the ones to buy on it left
  // out; only the list learned them (GROCERY-11). The first page, the ones added most and last: the
  // field shows a few, not all the list ever had
  suggestions(listId: number, text: string): Observable<GrocerySuggestion[]> {
    return this.http
      .get<Page<GrocerySuggestion>>(`/api/v1/grocery/lists/${listId}/suggestions`, {
        params: { text, offset: 0, limit: SUGGESTIONS },
      })
      .pipe(map((page) => page.items));
  }
}
