const COUNT = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

const BYTE_UNITS = ['B', 'kB', 'MB', 'GB', 'TB'];

export function formatCount(value: number): string {
  return Number.isNaN(value) ? '–' : COUNT.format(value);
}

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

export function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1).replace(/\.0$/, '')}%`;
}
