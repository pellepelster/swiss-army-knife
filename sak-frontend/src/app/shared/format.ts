const COUNT = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });

const BYTE_UNITS = ['B', 'kB', 'MB', 'GB', 'TB'];

// a count shortened in the locale of the browser, e.g. 1.2K
export function formatCount(value: number): string {
  return Number.isNaN(value) ? '–' : COUNT.format(value);
}

// bytes in decimal units, e.g. 2.5 MB
export function formatBytes(value: number): string {
  if (Number.isNaN(value)) {
    return '–';
  }
  let unit = 0;
  while (value >= 1000 && unit < BYTE_UNITS.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${unit === 0 ? value : value.toFixed(1).replace(/\.0$/, '')} ${BYTE_UNITS[unit]}`;
}
