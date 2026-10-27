import { Component, computed, input } from '@angular/core';
import { BarController, BarElement, CategoryScale, LinearScale, Tooltip } from 'chart.js';
import { BaseChartDirective, provideCharts } from 'ng2-charts';
import { ChartInputs, TimeSeries, toChartData, toChartOptions } from './chart-config';

// a stacked bar chart over the buckets of a metrics range, with a legend that sums each series;
// it is drawn on the sheet of the page it is part of
@Component({
  selector: 'app-time-series-chart',
  imports: [BaseChartDirective],
  providers: [
    provideCharts({
      registerables: [BarController, BarElement, CategoryScale, LinearScale, Tooltip],
    }),
  ],
  styles: `
    .title,
    .label {
      color: var(--muted);
      font-size: 0.85rem;
    }
    .value {
      font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace;
      font-weight: 600;
    }
    .swatch {
      display: inline-block;
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 2px;
    }
    .chart {
      position: relative;
      height: 280px;
    }
  `,
  template: `
    <div class="mb-3">
      <div class="title mb-2">{{ title() }}</div>
      @if (legend().length) {
        <ul class="list-unstyled d-flex flex-wrap gap-3 mb-0">
          @for (item of legend(); track item.key) {
            <li class="d-flex align-items-center gap-2">
              <span class="swatch" [style.background-color]="item.color"></span>
              <span class="label">{{ item.label }}</span>
              <span class="value">{{ item.value }}</span>
            </li>
          }
        </ul>
      }
    </div>
    <div class="chart">
      <canvas
        baseChart
        type="bar"
        role="img"
        [attr.aria-label]="title()"
        [data]="chartData()"
        [options]="chartOptions()"
      ></canvas>
    </div>
  `,
})
export class TimeSeriesChart {
  readonly title = input.required<string>();
  readonly buckets = input.required<string[]>();
  readonly stepSeconds = input.required<number>();
  readonly series = input.required<TimeSeries[]>();
  readonly valueFormatter = input<(value: number) => string>();
  readonly tooltipLines = input<(index: number) => string[]>();

  private readonly inputs = computed<ChartInputs>(() => ({
    buckets: this.buckets(),
    stepSeconds: this.stepSeconds(),
    series: this.series(),
    valueFormatter: this.valueFormatter(),
    tooltipLines: this.tooltipLines(),
  }));

  protected readonly chartData = computed(() => toChartData(this.inputs()));

  protected readonly chartOptions = computed(() => toChartOptions(this.inputs()));

  protected readonly legend = computed(() => {
    const format = this.valueFormatter() ?? ((value: number) => `${value}`);
    return this.series().length < 2
      ? []
      : this.series().map((series) => ({
          key: series.key,
          label: series.label,
          color: series.color,
          value: format(series.values.reduce((sum, value) => sum + value, 0)),
        }));
  });
}
