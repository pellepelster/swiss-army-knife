import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Page, PAGE_SIZE } from '../shared/api';

// who may sign in: an admin of the configuration of the deployment (no id, not deleted here) or a
// user an admin added, the role user
export interface User {
  id: number | null;
  email: string;
  name: string | null;
  configured: boolean;
}

// the users, only an admin reaches them
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  // the page of the users starting at the offset
  page(offset: number): Observable<Page<User>> {
    return this.http.get<Page<User>>('/api/v1/users', { params: { offset, limit: PAGE_SIZE } });
  }

  // no mail is sent, the user requests a link on the login page
  add(email: string, name: string | null): Observable<User> {
    return this.http.post<User>('/api/v1/users', { email, name });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/users/${id}`);
  }
}
