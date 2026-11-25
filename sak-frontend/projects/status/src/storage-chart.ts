import { Component, computed, input } from '@angular/core';
import { ArcElement, ChartConfiguration, DoughnutController, Tooltip } from 'chart.js';
import { BaseChartDirective, provideCharts } from 'ng2-charts';
import { formatBytes } from '@sak/core';

// one storage in the chart: what it is called, its bytes and its color
export interface StorageSlice {
  readonly label: string;
  readonly bytes: number;
  readonly color: string;
}

// a doughnut of what every storage keeps, its size in the tooltip; the legend with the numbers is
// the table next to it
@Component({
  selector: 'app-storage-chart',
  imports: [BaseChartDirective],
  providers: [provideCharts({ registerables: [DoughnutController, ArcElement, Tooltip] })],
  styles: `
    .chart {
      position: relative;
      height: 220px;
    }
  `,
  template: `
    <div class="chart">
      <canvas
        baseChart
        type="doughnut"
        role="img"
        [attr.aria-label]="title()"
        [data]="data()"
        [options]="options"
      ></canvas>
    </div>
  `,
})
export class StorageChart {
  readonly title = input.required<string>();
  readonly slices = input.required<readonly StorageSlice[]>();

  protected readonly data = computed<ChartConfiguration<'doughnut'>['data']>(() => ({
    labels: this.slices().map((slice) => slice.label),
    datasets: [
      {
        data: this.slices().map((slice) => slice.bytes),
        backgroundColor: this.slices().map((slice) => slice.color),
        borderWidth: 0,
      },
    ],
  }));

  protected readonly options: ChartConfiguration<'doughnut'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    cutout: '60%',
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (item) => ` ${formatBytes(Number(item.raw))}` } },
    },
  };
}
