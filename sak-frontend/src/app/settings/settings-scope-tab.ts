import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { apiErrorMessage } from '../shared/api';
import { SettingScopes } from './settings-page';
import { SettingsStore } from './settings-store';
import { RouterLink } from '@angular/router';
import { ScopeValues, SettingDefinition, SettingValue } from './settings.service';

type Requirement = NonNullable<SettingDefinition['requires']>;

// the settings of one scope, an input for each by its type, saved together with the values changed
// only; while the switch of the scope (enabledBy) is off, its other settings are greyed out. What is
// not saved is gone with another tab
@Component({
  selector: 'app-settings-scope-tab',
  imports: [RouterLink],
  template: `
    @if (loadError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    } @else if (scope(); as scope) {
      <form
        class="card card-body vstack gap-3"
        [attr.aria-label]="scope.label"
        [attr.data-testid]="'scope-' + scope.name"
        (submit)="$event.preventDefault(); save()"
      >
        <p class="text-body-secondary mb-0">{{ scope.description }}</p>

        @if (stored()) {
          @for (setting of scope.settings; track setting.name) {
            @switch (setting.type) {
              @case ('boolean') {
                <div class="form-check form-switch" [class.opacity-50]="off(setting)">
                  <input
                    #toggle
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    [disabled]="off(setting) || setting.readOnly"
                    [id]="inputId(setting)"
                    [checked]="value(setting) === true"
                    (change)="edit(setting, toggle.checked)"
                  />
                  <label class="form-check-label" [for]="inputId(setting)">{{
                    setting.label
                  }}</label>
                  <div class="form-text">{{ setting.description }}</div>
                  @if (setting.requires; as requires) {
                    @if (!met(requires)) {
                      <div class="form-text" data-testid="requires">
                        Needs
                        <a [routerLink]="['..', requires.scope]">{{ requiredLabel(requires) }}</a>
                        turned on.
                      </div>
                    }
                  }
                </div>
              }
              @case ('text') {
                <div [class.opacity-50]="off(setting)">
                  <label class="form-label" [for]="inputId(setting)">{{ setting.label }}</label>
                  <input
                    #text
                    [class]="
                      setting.readOnly ? 'form-control-plaintext font-monospace' : 'form-control'
                    "
                    [readOnly]="setting.readOnly"
                    type="text"
                    autocomplete="off"
                    [disabled]="off(setting)"
                    [id]="inputId(setting)"
                    [value]="shown(setting)"
                    [placeholder]="setting.mask === null ? '' : storedValue(setting)"
                    [attr.maxlength]="setting.maxLength"
                    (input)="edit(setting, text.value)"
                  />
                  <div class="form-text">{{ setting.description }}</div>
                </div>
              }
            }
          }
        } @else {
          <p class="text-body-secondary mb-0">Loading…</p>
        }

        @if (saveError(); as error) {
          <p class="alert alert-danger mb-0" role="alert" data-testid="save-error">{{ error }}</p>
        }

        <div class="d-flex flex-wrap justify-content-end gap-2">
          <button class="btn btn-primary" type="submit" [disabled]="!changed() || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            Save
          </button>
        </div>
      </form>
    }
  `,
})
export class SettingsScopeTab {
  private readonly scopes = inject(SettingScopes);
  private readonly store = inject(SettingsStore);

  // the route parameter, the name of the scope
  readonly name = input.required<string>({ alias: 'scope' });

  protected readonly scope = computed(
    () => this.scopes.scopes()?.find((scope) => scope.name === this.name()) ?? null,
  );

  protected readonly stored = computed(() => this.store.values()[this.name()] ?? null);

  protected readonly loadError = computed(() => {
    if (this.scopes.scopes() && !this.scope()) return 'There is no such scope of settings.';
    const error = this.store.errors()[this.name()];
    return error === undefined
      ? null
      : apiErrorMessage(error, 'The settings could not be loaded.', {
          403: 'Your role does not allow to see the settings.',
          404: 'There is no such scope of settings.',
        });
  });

  // the values changed here and not saved yet
  protected readonly edits = signal<ScopeValues>({});
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    // another tab shows another scope in the same component
    effect(() => {
      const name = this.name();
      untracked(() => {
        this.edits.set({});
        this.saveError.set(null);
        this.store.load(name);
      });
    });
    // the scopes of the settings required here, to tell whether they are on
    effect(() => {
      const name = this.name();
      const required = new Set(
        (this.scope()?.settings ?? [])
          .map((setting) => setting.requires?.scope)
          .filter((scope): scope is string => !!scope && scope !== name),
      );
      untracked(() => required.forEach((scope) => this.store.load(scope)));
    });
  }

  protected inputId(setting: SettingDefinition): string {
    return `setting-${this.name()}-${setting.name}`;
  }

  protected storedValue(setting: SettingDefinition): SettingValue {
    return this.stored()?.[setting.name] ?? setting.default;
  }

  // the value changed here, else the stored one, else the default
  protected value(setting: SettingDefinition): SettingValue {
    return this.edits()[setting.name] ?? this.storedValue(setting);
  }

  // a masked value is not shown, only its mask as the placeholder; what is typed is a new value
  protected shown(setting: SettingDefinition): SettingValue {
    return setting.mask === null ? this.value(setting) : (this.edits()[setting.name] ?? '');
  }

  // whether the setting is greyed out: its scope is turned off, as shown, and it is not the
  // switch, or a setting it requires is off; its stored value stays
  protected off(setting: SettingDefinition): boolean {
    const scope = this.scope();
    const switchSetting = scope?.settings.find((s) => s.name === scope.enabledBy);
    const scopeOff =
      !!switchSetting && setting !== switchSetting && this.value(switchSetting) !== true;
    return scopeOff || (!!setting.requires && !this.met(setting.requires));
  }

  // whether a required setting is on: its value, the switch of its scope and its own requirement;
  // here as shown, in another scope as stored
  protected met(requires: Requirement): boolean {
    const scope = this.scopes.scopes()?.find((s) => s.name === requires.scope);
    const setting = scope?.settings.find((s) => s.name === requires.setting);
    if (!scope || !setting) return false;
    const on = (name: string) =>
      requires.scope === this.name()
        ? this.value(scope.settings.find((s) => s.name === name)!) === true
        : this.store.values()[requires.scope]?.[name] === true;
    const switchOn = !scope.enabledBy || scope.enabledBy === setting.name || on(scope.enabledBy);
    return on(setting.name) && switchOn && (!setting.requires || this.met(setting.requires));
  }

  // the label of a required setting with its scope, e.g. "AI features of AI"
  protected requiredLabel(requires: Requirement): string {
    const scope = this.scopes.scopes()?.find((s) => s.name === requires.scope);
    const setting = scope?.settings.find((s) => s.name === requires.setting);
    return `${setting?.label ?? requires.setting} of ${scope?.label ?? requires.scope}`;
  }

  protected edit(setting: SettingDefinition, value: SettingValue): void {
    this.edits.update((edits) => ({ ...edits, [setting.name]: value }));
  }

  protected changed(): boolean {
    return Object.keys(this.changes()).length > 0;
  }

  protected async save(): Promise<void> {
    const changes = this.changes();
    if (!Object.keys(changes).length) return;
    const name = this.name();
    this.saving.set(true);
    this.saveError.set(null);
    try {
      await this.store.update(name, changes);
      if (this.name() === name) this.edits.set({});
    } catch (error: unknown) {
      this.saveError.set(
        apiErrorMessage(error, 'The settings could not be saved.', {
          403: 'Your role does not allow to change the settings.',
          404: 'There is no such scope of settings.',
        }),
      );
    } finally {
      this.saving.set(false);
    }
  }

  // the values changed here that differ from the stored ones
  private changes(): ScopeValues {
    const scope = this.scope();
    if (!scope) return {};
    return Object.fromEntries(
      scope.settings
        .filter((setting) => {
          if (setting.readOnly) return false;
          const edited = this.edits()[setting.name];
          return edited !== undefined && edited !== this.storedValue(setting);
        })
        .map((setting) => [setting.name, this.edits()[setting.name]]),
    );
  }
}
