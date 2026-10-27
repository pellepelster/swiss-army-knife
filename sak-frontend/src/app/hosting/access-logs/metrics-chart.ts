import { CHART_ACCENT } from './chart-config';
import { SiteMetrics } from './access-logs.service';

// the colors and labels of the status classes in the metrics of a bucket's logs

export type StatusClassKey = '2xx' | '3xx' | '4xx' | '5xx';

export const STATUS_CLASS_LABELS: Record<StatusClassKey, string> = {
  '2xx': 'Success',
  '3xx': 'Redirect',
  '4xx': 'Client error',
  '5xx': 'Server error',
};

export const STATUS_CLASS_COLORS: Record<StatusClassKey, string> = {
  '2xx': CHART_ACCENT,
  '3xx': '#7c857f',
  '4xx': '#c9a300',
  '5xx': '#e0604f',
};

export const BYTES_COLOR = CHART_ACCENT;

export function statusCodeLines(metrics: SiteMetrics, index: number): string[] {
  return metrics.statusCodes
    .filter((code) => code.values[index] > 0)
    .map((code) => `${code.status} × ${code.values[index]}`);
}
