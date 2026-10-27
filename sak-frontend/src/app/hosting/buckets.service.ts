import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { fetchAll, Page, PAGE_SIZE } from '../shared/api';
import { apiHost } from '../shared/api-url';

// objects, bytes and computedAt are missing while the bucket is still being counted or when it
// could not be counted
interface BucketStatistics {
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
  // <name>.<root_domain>, the bucket is served there as well; missing for names with dots,
  // which were allowed before
  subdomain?: string;
  createdAt: string;
  statistics: BucketStatistics;
}

export interface BucketSecret {
  accessKeyId: string;
  secretAccessKey: string;
}

// the names the backend accepts, each is the label of the bucket's subdomain; it also rejects
// the names of its own sites and the ones whose subdomain is served already
export const BUCKET_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/;

// the domains the bucket is served at, its own ones first, redirects lead to the first one
export function servedDomains(bucket: Bucket): string[] {
  return [...new Set([...bucket.domains, ...(bucket.subdomain ? [bucket.subdomain] : [])])];
}

// how long to wait before asking again while a bucket is still being counted
export const POLL_INTERVAL_MS = 3000;

// the buckets of the backend's s3 api
@Injectable({ providedIn: 'root' })
export class BucketsService {
  private readonly http = inject(HttpClient);

  // the page of the buckets starting at the offset
  page(offset: number): Observable<Page<Bucket>> {
    return this.http.get<Page<Bucket>>('/api/v1/static-hosting', {
      params: { offset, limit: PAGE_SIZE },
    });
  }

  // all of them, to choose one
  buckets(): Observable<Bucket[]> {
    return fetchAll<Bucket>(this.http, '/api/v1/static-hosting');
  }

  bucket(name: string): Observable<Bucket> {
    return this.http.get<Bucket>(`/api/v1/static-hosting/${encodeURIComponent(name)}`);
  }

  create(name: string, domains: string[]): Observable<Bucket> {
    return this.http.post<Bucket>('/api/v1/static-hosting', { name, domains });
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
}

// the endpoint of the s3 api, api. next to the apps of the frontend
export function s3Endpoint(hostname: string = location.hostname): string {
  return `https://${apiHost(hostname)}`;
}
