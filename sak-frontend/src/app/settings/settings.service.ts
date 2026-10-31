import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { fetchAll } from '../shared/api';

// a value of a setting, of the type its definition names
export type SettingValue = boolean | string;

// the values of the settings of one scope by their names, e.g. { enabled: false, api_key: '' }
export type ScopeValues = Record<string, SettingValue>;

// a setting as the backend describes it, the page chooses its input by the type
export interface SettingDefinition {
  name: string;
  label: string;
  description: string;
  type: 'boolean' | 'text';
  // the value while none is set
  default: SettingValue;
  // the most characters of a text, null for the other types
  maxLength: number | null;
  // how many characters of a value the backend shows, it masks the rest; null shows the whole value
  mask: number | null;
  // a value the backend gives, e.g. from its configuration, which can not be changed
  readOnly: boolean;
  // the boolean setting that must be on for this one to count, null when there is none
  requires: { scope: string; setting: string } | null;
}

export interface SettingScope {
  name: string;
  label: string;
  description: string;
  settings: SettingDefinition[];
  // the boolean setting that turns the scope on, null when it is always on
  enabledBy: string | null;
}

// the scopes of settings of the backend, their values read and set one scope at a time
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);

  scopes(): Observable<SettingScope[]> {
    return fetchAll<SettingScope>(this.http, '/api/v1/settings');
  }

  // the stored values, also of the settings that are off; effective as the apps use them, the
  // default for a setting that is off
  values(scope: string, effective = false): Observable<ScopeValues> {
    return this.http.get<ScopeValues>(`/api/v1/settings/${encodeURIComponent(scope)}`, {
      params: effective ? { effective: true } : {},
    });
  }

  // sets the values given, the others of the scope stay; answers all of the scope
  update(scope: string, values: ScopeValues): Observable<ScopeValues> {
    return this.http.put<ScopeValues>(`/api/v1/settings/${encodeURIComponent(scope)}`, values);
  }
}
