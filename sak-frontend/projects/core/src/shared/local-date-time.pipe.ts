import { Pipe, PipeTransform } from '@angular/core';

type DateTimeStyle = 'short' | 'medium' | 'long' | 'full';

// a date and time in the locale of the user's browser, not the locale the app was built with;
// null leaves the date or the time out
@Pipe({ name: 'localDateTime' })
export class LocalDateTimePipe implements PipeTransform {
  transform(
    value: Date | string | null | undefined,
    dateStyle: DateTimeStyle | null = 'short',
    timeStyle: DateTimeStyle | null = 'short',
  ): string {
    if (value === null || value === undefined || value === '') return '';
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: dateStyle ?? undefined,
      timeStyle: timeStyle ?? undefined,
    }).format(date);
  }
}
