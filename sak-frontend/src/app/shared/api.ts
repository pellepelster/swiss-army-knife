import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { EMPTY, expand, Observable, reduce } from 'rxjs';

// where a page starts in the whole list, how many items it has at most and how many there are
export interface Pagination {
  offset: number;
  limit: number;
  total: number;
}

// what every list endpoint of the api answers
export interface Page<T> {
  items: T[];
  pagination: Pagination;
}

// the most items a page of the api has
const MAX_LIMIT = 100;

// the items a list shows on one page
export const PAGE_SIZE = 50;

// where the last page starts that has items, e.g. after the last item of a page was deleted
export function lastPageOffset(pagination: Pagination): number {
  return Math.max(0, Math.floor((pagination.total - 1) / pagination.limit) * pagination.limit);
}

// every item of a list endpoint, asked for page by page; for choices and suggestions only, the
// lists shown are paginated
export function fetchAll<T>(
  http: HttpClient,
  url: string,
  params: Record<string, string | number> = {},
): Observable<T[]> {
  const page = (offset: number) =>
    http.get<Page<T>>(url, { params: { ...params, offset, limit: MAX_LIMIT } });
  return page(0).pipe(
    expand((current) => {
      const next = current.pagination.offset + current.items.length;
      return current.items.length > 0 && next < current.pagination.total ? page(next) : EMPTY;
    }),
    reduce((all, current) => [...all, ...current.items], [] as T[]),
  );
}

// the messages the api answered with (its validation errors and conflicts), empty when it
// answered without
export function errorMessages(error: unknown): string[] {
  if (!(error instanceof HttpErrorResponse)) return [];
  const body = error.error as { errors?: { message?: unknown }[] } | null;
  return Array.isArray(body?.errors)
    ? body.errors.map((e) => e.message).filter((m): m is string => typeof m === 'string')
    : [];
}

// what to tell about a failed request: the messages the api answered with, else the one given for
// its status (e.g. 403 for a role that does not allow it), else the fallback
export function apiErrorMessage(
  error: unknown,
  fallback: string,
  byStatus: Record<number, string> = {},
): string {
  const messages = errorMessages(error);
  if (messages.length) return messages.join(' ');
  return (error instanceof HttpErrorResponse && byStatus[error.status]) || fallback;
}
