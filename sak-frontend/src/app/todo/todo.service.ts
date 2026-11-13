import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TodoItem, TodoList, TodoPriority } from '../api/models';
import { fetchAll } from '../shared/api';
import { ListsService } from '../shared/list-service';

export type { TodoItem, TodoList, TodoPriority } from '../api/models';

// the todo lists of the signed in user and the shared ones, and their tasks
@Injectable({ providedIn: 'root' })
export class TodoService extends ListsService<TodoList> {
  protected readonly base = '/api/v1/todo';

  // the tasks, a done one in its place (TODO-3), by priority, the high ones first (TODO-5)
  items(listId: number): Observable<TodoItem[]> {
    return fetchAll<TodoItem>(this.http, `/api/v1/todo/lists/${listId}/items`);
  }

  // the due date yyyy-mm-dd, null for no day (TODO-4), and the priority (TODO-5)
  addItem(
    listId: number,
    title: string,
    dueDate: string | null,
    priority: TodoPriority = 'normal',
  ): Observable<TodoItem> {
    return this.http.post<TodoItem>('/api/v1/todo/items', { listId, title, dueDate, priority });
  }

  // done or open again (TODO-3)
  setDone(id: number, done: boolean): Observable<TodoItem> {
    return this.http.put<TodoItem>(`/api/v1/todo/items/${id}/done`, { done });
  }

  // the priority of a task, open or done (TODO-5)
  setPriority(id: number, priority: TodoPriority): Observable<TodoItem> {
    return this.http.put<TodoItem>(`/api/v1/todo/items/${id}/priority`, { priority });
  }

  // the due date of a task, yyyy-mm-dd, null takes it away (TODO-4)
  setDueDate(id: number, dueDate: string | null): Observable<TodoItem> {
    return this.http.put<TodoItem>(`/api/v1/todo/items/${id}/due-date`, { dueDate });
  }

  // the done tasks of the list among itemIds are deleted, a task done meanwhile stays (TODO-6)
  purge(listId: number, itemIds: readonly number[]): Observable<void> {
    return this.http.post<void>(`/api/v1/todo/lists/${listId}/purge`, { itemIds });
  }
}
