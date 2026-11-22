import { HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { fetchAll } from './api';

// a list of a list-based app as the api answers it (LIST-2, LIST-3): items is how many are open,
// e.g. the tasks of a todo list, the items to buy of a grocery list
export interface SharedList {
  readonly id: number;
  readonly name: string;
  readonly shared: boolean;
  readonly mine: boolean;
  readonly items: number;
  readonly icon?: string | null;
}

// the lists of a list-based app the signed in user sees, the own and the shared ones, created,
// changed and deleted below `<base>/lists`; each app extends it with its base and the rest of its
// api, e.g. TodoService
export abstract class ListsService<L extends SharedList> {
  protected readonly http = inject(HttpClient);
  // the api of the app, e.g. /api/v1/todo
  protected abstract readonly base: string;

  lists(): Observable<L[]> {
    return fetchAll<L>(this.http, `${this.base}/lists`);
  }

  createList(name: string, shared: boolean, icon: string | null): Observable<L> {
    return this.http.post<L>(`${this.base}/lists`, { name, shared, icon });
  }

  updateList(id: number, name: string, shared: boolean, icon: string | null): Observable<L> {
    return this.http.put<L>(`${this.base}/lists/${id}`, { name, shared, icon });
  }

  deleteList(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/lists/${id}`);
  }
}
