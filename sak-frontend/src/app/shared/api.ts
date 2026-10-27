import { HttpErrorResponse } from '@angular/common/http';

// what every list endpoint of the api answers
export interface Page<T> {
  items: T[];
  pagination: { offset: number; limit: number; total: number };
}

// the most a page has, lists shown whole are asked for in one
export const MAX_LIMIT = 500;

// the messages the api answered with (its validation errors and conflicts), empty when it
// answered without
export function errorMessages(error: unknown): string[] {
  if (!(error instanceof HttpErrorResponse)) return [];
  const body = error.error as { errors?: { message?: unknown }[] } | null;
  return Array.isArray(body?.errors)
    ? body.errors.map((e) => e.message).filter((m): m is string => typeof m === 'string')
    : [];
}
