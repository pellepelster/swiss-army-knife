import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
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

// the names the backend accepts, it also rejects ip addresses and the names of its own sites
export const BUCKET_NAME_PATTERN = /^(?!.*\.\.)[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/;

// how long to wait before asking again while a bucket is still being counted
export const POLL_INTERVAL_MS = 3000;

// the buckets of the backend's s3 api, the same the console shows
@Injectable({ providedIn: 'root' })
export class BucketsService {
  private readonly http = inject(HttpClient);

  // all of them, there are far less than a page of them
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
    return this.http.put<Bucket>(`/api/v1/buckets/${encodeURIComponent(name)}/domains`, {
      domains,
    });
  }

  secret(name: string): Observable<BucketSecret> {
    return this.http.get<BucketSecret>(`/api/v1/buckets/${encodeURIComponent(name)}/secret`);
  }
}

// the endpoint of the s3 api, s3. next to the apps of the frontend
export function s3Endpoint(hostname: string = location.hostname): string {
  return `https://s3.${hostname.replace(/^apps\./, '')}`;
}
