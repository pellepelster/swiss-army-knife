import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type HealthState = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN';

// what one health check found: name tells what was checked, message why, e.g. what failed;
// details are the parts it checked one by one, e.g. each domain
export interface HealthStatus {
  name: string;
  state: HealthState;
  message: string | null;
  details: HealthDetail[];
}

export interface HealthDetail {
  name: string;
  state: HealthState;
  message: string | null;
}

// the health checks of the backend
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  // runs every check, which takes a few seconds
  statuses(): Observable<HealthStatus[]> {
    return this.http.get<HealthStatus[]>('/api/v1/health');
  }
}
