import { LocalDateTimePipe } from './local-date-time.pipe';

describe('LocalDateTimePipe', () => {
  const pipe = new LocalDateTimePipe();
  const date = new Date('2026-10-27T08:30:00Z');

  it('formats in the locale of the browser', () => {
    expect(pipe.transform(date)).toBe(
      new Intl.DateTimeFormat(undefined, { dateStyle: 'short', timeStyle: 'short' }).format(date),
    );
    expect(pipe.transform(date.toISOString(), 'medium', 'medium')).toBe(
      new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(date),
    );
  });

  it('leaves the date or the time out', () => {
    expect(pipe.transform(date, null, 'medium')).toBe(
      new Intl.DateTimeFormat(undefined, { timeStyle: 'medium' }).format(date),
    );
    expect(pipe.transform(date, 'long', null)).toBe(
      new Intl.DateTimeFormat(undefined, { dateStyle: 'long' }).format(date),
    );
  });

  it('shows nothing for missing and invalid dates', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform('no date')).toBe('');
  });
});
