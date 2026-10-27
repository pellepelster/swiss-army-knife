import { Pipe, PipeTransform } from '@angular/core';

export type DateTimeStyle = 'short' | 'medium' | 'long' | 'full';

// a date and time in the locale of the user's browser, not the locale the app was built with
@Pipe({ name: 'localDateTime' })
export class LocalDateTimePipe implements PipeTransform {
  transform(
    value: Date | string | null | undefined,
    dateStyle: DateTimeStyle = 'short',
    timeStyle: DateTimeStyle = 'short',
  ): string {
    if (value === null || value === undefined || value === '') return '';
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat(undefined, { dateStyle, timeStyle }).format(date);
  }
}
