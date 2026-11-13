import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Bucket, BucketSecret } from '../api/models';
import { fetchAll, Page, PAGE_SIZE } from '../shared/api';
import { apiHost } from '../shared/api-url';

// a Bucket has no keys (see BucketSecret); objects, bytes and computedAt are missing while it is
// counted or when it could not be counted
export type { Bucket, BucketSecret } from '../api/models';

// a name is the label of the bucket's subdomain; the backend also rejects the names of its own
// sites and of subdomains served already
export const BUCKET_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/;

// its own domains first; redirects lead to the first one
export function servedDomains(bucket: Bucket): string[] {
  return [...new Set([...bucket.domains, ...(bucket.subdomain ? [bucket.subdomain] : [])])];
}

// how often to ask again while a bucket is counted
export const POLL_INTERVAL_MS = 3000;

// the buckets of the backend's s3 api
@Injectable({ providedIn: 'root' })
export class BucketsService {
  private readonly http = inject(HttpClient);

  page(offset: number): Observable<Page<Bucket>> {
    return this.http.get<Page<Bucket>>('/api/v1/static-hosting', {
      params: { offset, limit: PAGE_SIZE },
    });
  }

  buckets(): Observable<Bucket[]> {
    return fetchAll<Bucket>(this.http, '/api/v1/static-hosting');
  }

  bucket(name: string): Observable<Bucket> {
    return this.http.get<Bucket>(`/api/v1/static-hosting/${encodeURIComponent(name)}`);
  }

  create(name: string, domains: string[]): Observable<Bucket> {
    return this.http.post<Bucket>('/api/v1/static-hosting', { name, domains });
  }

  // it is no longer served and its key pair is refused, its objects stay
  delete(name: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/static-hosting/${encodeURIComponent(name)}`);
  }

  // serves the bucket at the domains instead of its current ones
  updateDomains(name: string, domains: string[]): Observable<Bucket> {
    return this.http.put<Bucket>(`/api/v1/static-hosting/${encodeURIComponent(name)}/domains`, {
      domains,
    });
  }

  secret(name: string): Observable<BucketSecret> {
    return this.http.get<BucketSecret>(`/api/v1/static-hosting/${encodeURIComponent(name)}/secret`);
  }

  // the key pair before stops working at once
  replaceSecret(name: string): Observable<BucketSecret> {
    return this.http.post<BucketSecret>(
      `/api/v1/static-hosting/${encodeURIComponent(name)}/secret`,
      null,
    );
  }
}

// the s3 api is at api. next to the host of the frontend
export function s3Endpoint(hostname: string = location.hostname): string {
  return `https://${apiHost(hostname)}`;
}
