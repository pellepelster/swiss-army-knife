import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Subscription } from 'rxjs';
import { ScopeValues, SettingsService } from './settings.service';

// the values of the scopes loaded, which the whole app shares: stored for the settings page,
// effective for the apps, e.g. the notes
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly service = inject(SettingsService);

  // the loads not answered yet, by scope
  private readonly loading = new Map<string, Subscription>();

  // the stored values by scope, a scope missing until it is loaded
  readonly values = signal<Record<string, ScopeValues>>({});

  // the values as the apps use them by scope, a setting that is off has its default
  readonly effective = signal<Record<string, ScopeValues>>({});

  private loadingEffective?: Subscription;

  // whether the notes offer the AI; off until the settings of the notes say otherwise, also while
  // they are loaded or could not be
  readonly notesAi = computed(() => this.effective()['notes']?.['ai'] === true);

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

  // the values of the scope as the apps use them; a load not answered yet is cancelled
  loadEffective(scope: string): void {
    this.loadingEffective?.unsubscribe();
    this.loadingEffective = this.service.values(scope, true).subscribe({
      next: (values) => this.effective.update((all) => ({ ...all, [scope]: values })),
      error: () => this.effective.update(({ [scope]: _, ...others }) => others),
    });
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
