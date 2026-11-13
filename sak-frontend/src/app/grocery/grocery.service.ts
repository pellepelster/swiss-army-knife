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

// checkedAt is missing while an item is to buy (GROCERY-3)
export type {
  GroceryCategory,
  GroceryItem,
  GroceryList,
  GroceryNewItem,
  GrocerySuggestion,
} from '../api/models';

export type GroceryItemValues = Omit<GroceryNewItem, 'listId'>;

// (GROCERY-16)
export type GrocerySettings = components['schemas']['GrocerySettingsResponse'];
export type GroceryDiscoveredItem = components['schemas']['GroceryDiscoveredItem'];

// how many suggestions the field of a new item shows at most
const SUGGESTIONS = 10;

// the grocery lists of the user and the shared ones, their items and the suggestions
@Injectable({ providedIn: 'root' })
export class GroceryService extends ListsService<GroceryList> {
  protected readonly base = '/api/v1/grocery';

  // with checked also the bought ones (GROCERY-14)
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

  // null for none; also for the next item of its name on the list
  setCategory(id: number, categoryId: number | null): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}/category`, { categoryId });
  }

  // bought: it goes from the list and stays as its history (GROCERY-3)
  checkItem(id: number): Observable<void> {
    return this.http.post<void>(`/api/v1/grocery/items/${id}/check`, null);
  }

  // for good, also from the suggestions
  deleteItem(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/grocery/items/${id}`);
  }

  // a photo, scaled down already, into the file storage
  uploadPhoto(photo: Blob): Observable<{ id: number }> {
    return this.http.post<{ id: number }>('/api/v1/files', photo, {
      headers: { 'Content-Type': photo.type || 'application/octet-stream' },
    });
  }

  // also for the next item of its name on the list (GROCERY-12)
  attachPhoto(id: number, fileId: number): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}/photo`, { fileId });
  }

  removePhoto(id: number): Observable<GroceryItem> {
    return this.http.delete<GroceryItem>(`/api/v1/grocery/items/${id}/photo`);
  }

  settings(): Observable<GrocerySettings> {
    return this.http.get<GrocerySettings>('/api/v1/grocery/settings');
  }

  // nothing is added yet (GROCERY-16)
  discover(fileId: number): Observable<GroceryDiscoveredItem[]> {
    return this.http
      .post<components['schemas']['GroceryDiscoveredResponse']>(
        `/api/v1/grocery/discover/${fileId}`,
        null,
      )
      .pipe(map((discovered) => discovered.items));
  }

  // in their order (GROCERY-13)
  categories(listId: number): Observable<GroceryCategory[]> {
    return fetchAll<GroceryCategory>(this.http, `/api/v1/grocery/lists/${listId}/categories`);
  }

  // added as the last one
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

  // all ids of the categories of the list, each once, in the new order
  orderCategories(listId: number, ids: number[]): Observable<GroceryCategory[]> {
    return this.http
      .put<Page<GroceryCategory>>(`/api/v1/grocery/lists/${listId}/categories/order`, { ids })
      .pipe(map((page) => page.items));
  }

  // the names on the list before that contain the text, the ones to buy left out (GROCERY-11); only
  // the first page, the ones added most and last
  suggestions(listId: number, text: string): Observable<GrocerySuggestion[]> {
    return this.http
      .get<Page<GrocerySuggestion>>(`/api/v1/grocery/lists/${listId}/suggestions`, {
        params: { text, offset: 0, limit: SUGGESTIONS },
      })
      .pipe(map((page) => page.items));
  }
}
