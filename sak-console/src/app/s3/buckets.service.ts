import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { MAX_LIMIT, Page } from '../shared/api';

// objects, bytes and computedAt are missing while the bucket is still being counted or when it
// could not be counted
export interface BucketStatistics {
  computing: boolean;
  failed: boolean;
  objects?: number;
  bytes?: number;
  computedAt?: string;
}

// without its keys, they are fetched on their own
export interface Bucket {
  name: string;
  domains: string[];
  createdAt: string;
  statistics: BucketStatistics;
}

export interface BucketSecret {
  accessKeyId: string;
  secretAccessKey: string;
}

// the names the controller accepts, it also rejects ip addresses and the names of its own sites
export const BUCKET_NAME_PATTERN = /^(?!.*\.\.)[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/;

@Injectable({ providedIn: 'root' })
export class BucketsService {
  private readonly http = inject(HttpClient);

  // all of them, the console has far less than a page of them
  buckets(): Observable<Bucket[]> {
    return this.http
      .get<Page<Bucket>>('/api/v1/buckets', { params: { limit: MAX_LIMIT } })
      .pipe(map((page) => page.items));
  }

  bucket(name: string): Observable<Bucket> {
    return this.http.get<Bucket>(`/api/v1/buckets/${encodeURIComponent(name)}`);
  }

  create(name: string, domains: string[]): Observable<Bucket> {
    return this.http.post<Bucket>('/api/v1/buckets', { name, domains });
  }

  // serves the bucket at the domains instead of its current ones
  updateDomains(name: string, domains: string[]): Observable<Bucket> {
    return this.http.put<Bucket>(`/api/v1/buckets/${encodeURIComponent(name)}/domains`, { domains });
  }

  secret(name: string): Observable<BucketSecret> {
    return this.http.get<BucketSecret>(`/api/v1/buckets/${encodeURIComponent(name)}/secret`);
  }
}
