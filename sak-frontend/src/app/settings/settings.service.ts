import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SettingScope, SettingValue } from '../api/models';
import { fetchAll } from '../shared/api';

// a value of a setting, of the type its definition names; a setting as the backend describes it,
// the page chooses its input by the type
export type { SettingDefinition, SettingScope, SettingValue } from '../api/models';

// the values of the settings of one scope by their names, e.g. { enabled: false, api_key: '' }
export type ScopeValues = Record<string, SettingValue>;

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
