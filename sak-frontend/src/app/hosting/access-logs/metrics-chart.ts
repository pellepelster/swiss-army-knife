import { CATEGORICAL_COLORS, CHART_ACCENT } from './chart-config';
import { SiteMetrics } from './access-logs.service';

// the colors and groupings of the blcks page metrics

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

export type ClientFamilyKey =
  'browser' | 'ai' | 'search' | 'seo' | 'feed' | 'script' | 'scanner' | 'other';

export const CLIENT_FAMILY_OF: Record<string, ClientFamilyKey> = {
  chrome: 'browser',
  firefox: 'browser',
  safari: 'browser',
  edge: 'browser',
  'other-browser': 'browser',
  anthropic: 'ai',
  openai: 'ai',
  perplexity: 'ai',
  'ai-crawler': 'ai',
  google: 'search',
  bing: 'search',
  'search-engine': 'search',
  amazon: 'search',
  meta: 'search',
  'seo-crawler': 'seo',
  'feed-reader': 'feed',
  sdk: 'script',
  'security-scanner': 'scanner',
  exploit: 'scanner',
  other: 'other',
};

export const CLIENT_FAMILY_ORDER: ClientFamilyKey[] = [
  'browser',
  'ai',
  'search',
  'seo',
  'feed',
  'script',
  'scanner',
  'other',
];

export const CLIENT_FAMILY_LABELS: Record<ClientFamilyKey, string> = {
  browser: 'Browsers',
  ai: 'AI',
  search: 'Search engines',
  seo: 'SEO crawlers',
  feed: 'Feed readers',
  script: 'Scripts',
  scanner: 'Scanners',
  other: 'Other',
};

export const CLIENT_FAMILY_COLORS: Record<ClientFamilyKey, string> = {
  browser: CATEGORICAL_COLORS.blue,
  ai: CATEGORICAL_COLORS.orange,
  search: CATEGORICAL_COLORS.green,
  seo: CATEGORICAL_COLORS.amber,
  feed: CATEGORICAL_COLORS.pink,
  script: CATEGORICAL_COLORS.forest,
  scanner: CATEGORICAL_COLORS.indigo,
  other: CATEGORICAL_COLORS.red,
};

export const USER_AGENT_LABELS: Record<string, string> = {
  anthropic: 'Anthropic',
  openai: 'OpenAI',
  google: 'Google',
  bing: 'Bing',
  meta: 'Meta',
  amazon: 'Amazon / Apple',
  perplexity: 'Perplexity',
  'ai-crawler': 'Other AI crawlers',
  'search-engine': 'Other search engines',
  'seo-crawler': 'SEO crawlers',
  'feed-reader': 'Feed readers',
  'security-scanner': 'Security scanners',
  exploit: 'Exploits',
  sdk: 'Scripts and SDKs',
  chrome: 'Chrome',
  firefox: 'Firefox',
  safari: 'Safari',
  edge: 'Edge',
  'other-browser': 'Other browsers',
  other: 'Other',
};

export function statusCodeLines(metrics: SiteMetrics, index: number): string[] {
  return metrics.statusCodes
    .filter((code) => code.values[index] > 0)
    .map((code) => `${code.status} × ${code.values[index]}`);
}
