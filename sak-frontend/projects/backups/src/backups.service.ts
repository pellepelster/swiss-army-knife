import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Page, PAGE_SIZE, ResticRepository } from '@sak/core';

export type { ResticRepository } from '@sak/core';

// the repositories of restic the instance keeps for the signed in user (BACKUP-1)
@Injectable({ providedIn: 'root' })
export class BackupsService {
  private readonly http = inject(HttpClient);

  page(offset: number): Observable<Page<ResticRepository>> {
    return this.http.get<Page<ResticRepository>>('/api/v1/restic/repositories', {
      params: { offset, limit: PAGE_SIZE },
    });
  }

  // a soft delete with its backup plans (BACKUP-9)
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/v1/restic/repositories/${id}`);
  }
}
