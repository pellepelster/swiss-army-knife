import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Subscription } from 'rxjs';
import { ScopeValues, SettingsService } from './settings.service';

// the values of the scopes loaded, which the whole app shares, for the settings page
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly service = inject(SettingsService);

  // the loads not answered yet, by scope
  private readonly loading = new Map<string, Subscription>();

  // the stored values by scope, a scope missing until it is loaded
  readonly values = signal<Record<string, ScopeValues>>({});

  // why a scope could not be loaded, by scope
  readonly errors = signal<Record<string, unknown>>({});

  // a load of the scope not answered yet is cancelled
  load(scope: string): void {
    this.loading.get(scope)?.unsubscribe();
    this.errors.update(({ [scope]: _, ...others }) => others);
    this.loading.set(
      scope,
      this.service.values(scope).subscribe({
        next: (values) => this.take(scope, values),
        error: (error: unknown) => {
          this.values.update(({ [scope]: _, ...others }) => others);
          this.errors.update((errors) => ({ ...errors, [scope]: error }));
        },
      }),
    );
  }

  // sets the values given of the scope and takes all of it the backend answers; rejects with the
  // error of the api
  async update(scope: string, values: ScopeValues): Promise<void> {
    this.take(scope, await firstValueFrom(this.service.update(scope, values)));
  }

  private take(scope: string, values: ScopeValues): void {
    this.values.update((all) => ({ ...all, [scope]: values }));
  }
}
