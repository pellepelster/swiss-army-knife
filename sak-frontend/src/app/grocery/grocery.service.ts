import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { fetchAll } from '../shared/api';

export interface GroceryCategory {
  name: string;
  label: string;
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
  category: string | null;
  // the file of its photo, null without one
  photoId?: number | null;
}

// what is given of an item
export interface GroceryItemValues {
  name: string;
  amount: number | null;
  unit: string | null;
  category: string | null;
}

// an item added before, with the quantity and the category used last
export interface GrocerySuggestion {
  id: number;
  name: string;
  amount: number | null;
  unit: string | null;
  category: string | null;
}

// the grocery lists of the signed in user and the shared ones, their items and the suggestions
@Injectable({ providedIn: 'root' })
export class GroceryService {
  private readonly http = inject(HttpClient);

  categories(): Observable<GroceryCategory[]> {
    return fetchAll<GroceryCategory>(this.http, '/api/v1/grocery/categories');
  }

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

  // the ones to buy first, by their category, the bought ones after them
  items(listId: number): Observable<GroceryItem[]> {
    return fetchAll<GroceryItem>(this.http, `/api/v1/grocery/lists/${listId}/items`);
  }

  addItem(listId: number, values: GroceryItemValues): Observable<GroceryItem> {
    return this.http.post<GroceryItem>('/api/v1/grocery/items', { listId, ...values });
  }

  updateItem(id: number, values: GroceryItemValues): Observable<GroceryItem> {
    return this.http.put<GroceryItem>(`/api/v1/grocery/items/${id}`, values);
  }

  // checks the item: it is bought and deleted
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

  // the items added to the list before whose name contains the text, the ones to buy on it left
  // out; only the list learned them (GROCERY-11)
  suggestions(listId: number, text: string): Observable<GrocerySuggestion[]> {
    return fetchAll<GrocerySuggestion>(this.http, `/api/v1/grocery/lists/${listId}/suggestions`, {
      text,
    });
  }
}
