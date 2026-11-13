import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SettingScope, SettingValue } from '../api/models';
import { fetchAll } from '../shared/api';

// a value has the type its definition names; the page chooses the input by that type
export type { SettingDefinition, SettingScope, SettingValue } from '../api/models';

// the values of the settings of one scope by their names, e.g. { enabled: false, api_key: '' }
export type ScopeValues = Record<string, SettingValue>;

// the settings are read and set one scope at a time
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);

  scopes(): Observable<SettingScope[]> {
    return fetchAll<SettingScope>(this.http, '/api/v1/settings');
  }

  // the stored values, also of the settings that are off
  values(scope: string): Observable<ScopeValues> {
    return this.http.get<ScopeValues>(`/api/v1/settings/${encodeURIComponent(scope)}`);
  }

  // the others of the scope stay; answers the whole scope
  update(scope: string, values: ScopeValues): Observable<ScopeValues> {
    return this.http.put<ScopeValues>(`/api/v1/settings/${encodeURIComponent(scope)}`, values);
  }
}
