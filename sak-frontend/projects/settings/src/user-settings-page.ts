import { HttpClient } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  apiErrorMessage,
  I18n,
  PageHeader,
  SettingValue,
  TranslatePipe,
  UserSetting,
  UserSettingScope,
} from '@sak/core';
import { firstValueFrom } from 'rxjs';

// the values changed and not saved yet, by scope and setting
type Edited = Record<string, Record<string, SettingValue>>;

// /user/settings: the settings of the user signed in, for them alone, e.g. the AI of an app (PRIVACY-2);
// every scope a card of switches, one Save for all (WEB-3) that sends the changed values of each scope.
// A setting that does not count now is greyed out and says why; one that is on can still be turned off
@Component({
  selector: 'app-user-settings-page',
  imports: [PageHeader, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'shared.my_settings' | t }}</h1>
    </header>

    @if (loadError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    } @else if (scopes(); as scopes) {
      <form class="vstack gap-3" (submit)="$event.preventDefault(); save()">
        <p class="text-body-secondary mb-0">{{ 'settings.mine_intro' | t }}</p>
        @for (scope of scopes; track scope.name) {
          <section
            class="card card-body vstack gap-3"
            [attr.aria-label]="scope.label"
            [attr.data-testid]="'scope-' + scope.name"
          >
            <h2 class="h5 mb-0">{{ scope.label }}</h2>
            @for (setting of scope.settings; track setting.name) {
              @if (setting.type === 'boolean') {
                <div class="form-check form-switch" [class.opacity-50]="!setting.available">
                  <input
                    #toggle
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    [disabled]="locked(scope, setting)"
                    [id]="inputId(scope, setting)"
                    [checked]="value(scope, setting) === true"
                    (change)="edit(scope, setting, toggle.checked)"
                  />
                  <label class="form-check-label" [for]="inputId(scope, setting)">{{
                    setting.label
                  }}</label>
                  <div class="form-text">{{ setting.description }}</div>
                  @if (setting.unavailableReason; as reason) {
                    <div class="form-text" data-testid="unavailable">{{ reason }}</div>
                  }
                </div>
              }
            }
          </section>
        }

        @if (saveError(); as error) {
          <p class="alert alert-danger mb-0" role="alert" data-testid="save-error">{{ error }}</p>
        } @else if (saved()) {
          <p class="text-success mb-0" role="status" data-testid="saved">
            {{ 'settings.saved' | t }}
          </p>
        }

        <!-- one group (UX-9) -->
        <div class="d-flex justify-content-end">
          <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
            <button class="btn btn-primary" type="submit" [disabled]="!changed() || saving()">
              @if (saving()) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              }
              {{ 'shared.save' | t }}
            </button>
          </div>
        </div>
      </form>
    } @else {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class UserSettingsPage {
  private readonly http = inject(HttpClient);
  private readonly i18n = inject(I18n);

  protected readonly scopes = signal<UserSettingScope[] | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly edited = signal<Edited>({});
  protected readonly changed = computed(() =>
    Object.values(this.edited()).some((values) => Object.keys(values).length > 0),
  );
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saved = signal(false);

  constructor() {
    this.http
      .get<{ scopes: UserSettingScope[] }>('/api/v1/user/settings')
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (settings) => this.scopes.set(settings.scopes),
        error: (error: unknown) =>
          this.loadError.set(apiErrorMessage(error, this.i18n.t('settings.not_loaded'))),
      });
  }

  protected inputId(scope: UserSettingScope, setting: UserSetting): string {
    return `user-setting-${scope.name}-${setting.name}`;
  }

  protected value(scope: UserSettingScope, setting: UserSetting): SettingValue {
    return this.edited()[scope.name]?.[setting.name] ?? setting.value;
  }

  // while it does not count it can only go back to its default, e.g. the AI turned off
  protected locked(scope: UserSettingScope, setting: UserSetting): boolean {
    return !setting.available && this.value(scope, setting) === setting.default;
  }

  protected edit(scope: UserSettingScope, setting: UserSetting, value: SettingValue): void {
    this.saved.set(false);
    this.edited.update((edited) => {
      const values = { ...edited[scope.name] };
      if (value === setting.value) delete values[setting.name];
      else values[setting.name] = value;
      return { ...edited, [scope.name]: values };
    });
  }

  // each scope with a change, one after the other; a scope saved stays saved when a later one fails
  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);
    try {
      for (const [name, values] of Object.entries(this.edited())) {
        if (!Object.keys(values).length) continue;
        const scope = await firstValueFrom(
          this.http.put<UserSettingScope>(
            `/api/v1/user/settings/${encodeURIComponent(name)}`,
            values,
          ),
        );
        this.scopes.update(
          (scopes) => scopes?.map((it) => (it.name === name ? scope : it)) ?? null,
        );
        this.edited.update(({ [name]: _, ...rest }) => rest);
      }
      this.saved.set(true);
    } catch (error: unknown) {
      this.saveError.set(apiErrorMessage(error, this.i18n.t('settings.not_saved')));
    } finally {
      this.saving.set(false);
    }
  }
}
