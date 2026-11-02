import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { fetchAll, Page } from '../shared/api';

// a category of a list, the groups of its items in the order the list answers them (GROCERY-13)
export interface GroceryCategory {
  id: number;
  name: string;
  // one emoji, null for none
  icon: string | null;
}

export interface GroceryList {
  id: number;
  name: string;
  // of all users
  shared: boolean;
  // of the signed in user, who renames, shares and deletes it
  mine: boolean;
  // how many items are to buy on it
  items: number;
  // one emoji, null shows the first letter of the name
  icon: string | null;
}

export interface GroceryItem {
  id: number;
  listId: number;
  name: string;
  amount: number | null;
  unit: string | null;
  // a category of its list, null for none
  categoryId: number | null;
  // the file of its photo, null without one
  photoId?: number | null;
  // when it was bought, missing while it is to buy (GROCERY-3)
  checkedAt?: string | null;
}

// what is given of an item
export interface GroceryItemValues {
  name: string;
  amount: number | null;
  unit: string | null;
  // a category of the list; a new item without one gets the one the list learned of its name
  categoryId: number | null;
}

// an item added before, with the quantity and the category used last
export interface GrocerySuggestion {
  id: number;
  name: string;
  amount: number | null;
  unit: string | null;
  categoryId: number | null;
}

// the grocery lists of the signed in user and the shared ones, their items and the suggestions
@Injectable({ providedIn: 'root' })
export class GroceryService {
  private readonly http = inject(HttpClient);

  lists(): Observable<GroceryList[]> {
    return fetchAll<GroceryList>(this.http, '/api/v1/grocery/lists');
  }

  createList(name: string, shared: boolean, icon: string | null): Observable<GroceryList> {
    return this.http.post<GroceryList>('/api/v1/grocery/lists', { name, shared, icon });
  }

  updateList(
    id: number,
    name: string,
    shared: boolean,
    icon: string | null,
  ): Observable<GroceryList> {
    return this.http.put<GroceryList>(`/api/v1/grocery/lists/${id}`, { name, shared, icon });
  }

  deleteList(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/grocery/lists/${id}`);
  }

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
  // out; only the list learned them (GROCERY-11)
  suggestions(listId: number, text: string): Observable<GrocerySuggestion[]> {
    return fetchAll<GrocerySuggestion>(this.http, `/api/v1/grocery/lists/${listId}/suggestions`, {
      text,
    });
  }
}
