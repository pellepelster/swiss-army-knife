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
}

export interface GroceryItem {
  id: number;
  listId: number;
  name: string;
  amount: number | null;
  unit: string | null;
  category: string | null;
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

  createList(name: string, shared: boolean): Observable<GroceryList> {
    return this.http.post<GroceryList>('/api/v1/grocery/lists', { name, shared });
  }

  updateList(id: number, name: string, shared: boolean): Observable<GroceryList> {
    return this.http.put<GroceryList>(`/api/v1/grocery/lists/${id}`, { name, shared });
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

  // the items added before whose name contains the text, the ones to buy on the list left out
  suggestions(text: string, listId: number | null): Observable<GrocerySuggestion[]> {
    return fetchAll<GrocerySuggestion>(this.http, '/api/v1/grocery/suggestions', {
      text,
      ...(listId === null ? {} : { listId }),
    });
  }
}
