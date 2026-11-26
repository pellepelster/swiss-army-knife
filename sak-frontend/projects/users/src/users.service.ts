import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Page, PAGE_SIZE, User } from '@sak/core';

// an admin of the configuration (no id, not deleted here) or a user an admin added (role user)
export type { User } from '@sak/core';

// only an admin reaches them
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  page(offset: number): Observable<Page<User>> {
    return this.http.get<Page<User>>('/api/v1/users', { params: { offset, limit: PAGE_SIZE } });
  }

  // no mail is sent, the user requests a link on the login page
  add(email: string, name: string | null): Observable<User> {
    return this.http.post<User>('/api/v1/users', { email, name });
  }

  // an e-mail with the links to the android apps
  sendAppLink(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/users/app-link', { email });
  }

  // every session of the user ends, the user stays (SEC-4)
  signOut(id: number): Observable<void> {
    return this.http.post<void>(`/api/v1/users/${id}/sign-out`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/users/${id}`);
  }
}
