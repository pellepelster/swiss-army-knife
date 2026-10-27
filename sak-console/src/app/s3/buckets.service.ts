import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// objects, bytes and computedAt are missing while the bucket is still being counted or when it
// could not be counted
export interface BucketStatistics {
  computing: boolean;
  failed: boolean;
  objects?: number;
  bytes?: number;
  computedAt?: string;
}

export interface Bucket {
  name: string;
  domains: string[];
  statistics: BucketStatistics;
}

@Injectable({ providedIn: 'root' })
export class BucketsService {
  private readonly http = inject(HttpClient);

  buckets(): Observable<Bucket[]> {
    return this.http.get<Bucket[]>('/api/v1/buckets');
  }
}
