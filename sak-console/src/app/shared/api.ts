import { HttpErrorResponse } from '@angular/common/http';

// what every list endpoint of the api answers
export interface Page<T> {
  items: T[];
  pagination: { offset: number; limit: number; total: number };
}

// the body of the api's validation errors and conflicts
export interface ErrorResponse {
  errors: { message: string }[];
}

// the most a page has, lists the console shows whole are asked for in one
export const MAX_LIMIT = 500;

// the messages the api answered with, empty when it answered without
export function errorMessages(error: unknown): string[] {
  if (!(error instanceof HttpErrorResponse)) return [];
  const body = error.error as Partial<ErrorResponse> | null;
  return Array.isArray(body?.errors) ? body.errors.map((e) => e.message).filter((m) => typeof m === 'string') : [];
}
