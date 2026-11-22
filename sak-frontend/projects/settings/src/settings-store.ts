import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Subscription } from 'rxjs';
import { ScopeValues, SettingsService } from './settings.service';

// the values of the loaded scopes, shared by the whole app
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly service = inject(SettingsService);

  // by scope
  private readonly loading = new Map<string, Subscription>();

  // by scope, missing until loaded
  readonly values = signal<Record<string, ScopeValues>>({});

  // by scope
  readonly errors = signal<Record<string, unknown>>({});

  // cancels a load of the scope not answered yet
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

  // sends only the values given, takes the whole scope answered; rejects with the api error
  async update(scope: string, values: ScopeValues): Promise<void> {
    this.take(scope, await firstValueFrom(this.service.update(scope, values)));
  }

  private take(scope: string, values: ScopeValues): void {
    this.values.update((all) => ({ ...all, [scope]: values }));
  }
}
