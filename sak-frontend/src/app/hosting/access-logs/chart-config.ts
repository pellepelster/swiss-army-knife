import { ChartConfiguration, TooltipItem } from 'chart.js';

// time series charts in the style of the blcks console, without its deployment markers

export interface TimeSeries {
  key: string;
  label: string;
  color: string;
  values: number[];
}

export interface ChartInputs {
  buckets: string[];
  stepSeconds: number;
  series: TimeSeries[];
  valueFormatter?: (value: number) => string;
  tooltipLines?: (index: number) => string[];
}

export const CATEGORICAL_COLORS = {
  blue: '#2a78d6',
  orange: '#eb6834',
  green: '#1baf7a',
  amber: '#eda100',
  pink: '#e87ba4',
  forest: '#008300',
  indigo: '#4a3aa7',
  red: '#e34948',
};

export const CHART_ACCENT = '#30c070';

// the card background, the gap between stacked segments
const CHART_SURFACE = '#faf8f2';

const SEGMENT_GAP = 2;

const SIX_HOURS_SECONDS = 21600;
const ONE_DAY_SECONDS = 86400;

export function bucketLabel(bucket: string, stepSeconds: number): string {
  const date = new Date(bucket);
  if (stepSeconds < SIX_HOURS_SECONDS) {
    return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  if (stepSeconds < ONE_DAY_SECONDS) {
    return date.toLocaleString(undefined, { weekday: 'short', hour: '2-digit' });
  }
  return date.toLocaleDateString(undefined, { day: '2-digit', month: 'short' });
}

export function toChartData(inputs: ChartInputs): ChartConfiguration<'bar'>['data'] {
  return {
    labels: inputs.buckets.map((bucket) => bucketLabel(bucket, inputs.stepSeconds)),
    datasets: inputs.series.map((series) => ({
      label: series.label,
      data: series.values,
      backgroundColor: series.color,
      borderColor: CHART_SURFACE,
      borderWidth: { top: SEGMENT_GAP, right: 0, bottom: 0, left: 0 },
      borderSkipped: false,
    })),
  };
}

export function toChartOptions(inputs: ChartInputs): ChartConfiguration<'bar'>['options'] {
  const format = inputs.valueFormatter ?? ((value: number) => `${value}`);
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: 'index', intersect: false },
    scales: {
      x: { stacked: true, grid: { display: false } },
      y: {
        stacked: true,
        beginAtZero: true,
        ticks: { precision: 0, callback: (value) => format(Number(value)) },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          label: (item: TooltipItem<'bar'>) =>
            `${item.dataset.label}: ${format(item.parsed.y ?? 0)}`,
          afterBody: (items: TooltipItem<'bar'>[]) =>
            inputs.tooltipLines?.(items[0].dataIndex) ?? [],
        },
      },
    },
  };
}
