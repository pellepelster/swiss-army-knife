import { HttpErrorResponse } from '@angular/common/http';
import { apiErrorMessage } from './api';

describe('apiErrorMessage', () => {
  const failed = (status: number, error: unknown = null) =>
    new HttpErrorResponse({ status, error });

  it('tells what the api answered, else what the status means, else the fallback', () => {
    const answered = failed(400, { errors: [{ message: 'One.' }, { message: 'Two.' }] });
    expect(apiErrorMessage(answered, 'Failed.', { 400: 'Bad.' })).toBe('One. Two.');
    expect(apiErrorMessage(failed(403), 'Failed.', { 403: 'Not your role.' })).toBe(
      'Not your role.',
    );
    expect(apiErrorMessage(failed(500), 'Failed.', { 403: 'Not your role.' })).toBe('Failed.');
    expect(apiErrorMessage(new Error('offline'), 'Failed.')).toBe('Failed.');
  });
});
