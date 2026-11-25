import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HealthStatus, Status } from '@sak/core';

// what one health check found: name tells what was checked, message why, e.g. what failed;
// details are the parts it checked one by one, e.g. each domain
export type {
  HealthDetail,
  HealthState,
  HealthStatus,
  Status,
  StorageTotal,
  StorageUsage,
} from '@sak/core';

@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly http = inject(HttpClient);

  // runs every check, which takes a few seconds
  health(): Observable<HealthStatus[]> {
    return this.http.get<HealthStatus[]>('/api/v1/health');
  }

  // the health of the whole system, its checks run again, and what the storages keep as counted last
  status(): Observable<Status> {
    return this.http.get<Status>('/api/v1/status');
  }
}
