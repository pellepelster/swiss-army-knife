import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ResticRepository } from '../api/models';
import { Page, PAGE_SIZE } from '../shared/api';

export type { ResticRepository } from '../api/models';

// the repositories of restic the instance keeps for the signed in user (BACKUP-1)
@Injectable({ providedIn: 'root' })
export class BackupsService {
  private readonly http = inject(HttpClient);

  page(offset: number): Observable<Page<ResticRepository>> {
    return this.http.get<Page<ResticRepository>>('/api/v1/restic/repositories', {
      params: { offset, limit: PAGE_SIZE },
    });
  }
}
