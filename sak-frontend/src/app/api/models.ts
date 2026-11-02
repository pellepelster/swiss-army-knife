// the shapes of the api by readable names, from the types generated of openapi.json (schema.ts). A
// field the schema marks optional may be missing or null alike: the backend leaves out a nullable
// field with a default when it is null, so the apps test `== null`, never `=== null` alone.
// Where the backend sends a fixed set of values as a plain string (or the schema of a JsonPrimitive
// says string), the alias narrows the field to that set; each one says so
import { components } from './schema';

type Schemas = components['schemas'];

// a page of a list endpoint (the items differ by endpoint, the schema has no named page)
export type Pagination = Schemas['PaginationResponse'];

// auth
export type ExchangeResponse = Schemas['ExchangeResponse'];
export type WhoAmI = Schemas['WhoAmIResponse'];
// who signs in to the deployment: multi, users by e-mail and the admin by password; single, only
// the admin by password (the backend sends the name of SakUserMode as a string)
export type UserMode = 'single' | 'multi';
export type DeploymentInfo = Omit<Schemas['InfoResponse'], 'userMode'> & { userMode: UserMode };

// grocery
export type GroceryCategory = Schemas['GroceryCategoryResponse'];
export type GroceryList = Schemas['GroceryListResponse'];
export type GroceryItem = Schemas['GroceryItemResponse'];
export type GroceryNewItem = Schemas['GroceryNewItemRequest'];
export type GrocerySuggestion = Schemas['GrocerySuggestionResponse'];

// notes (the color is one of NOTE_COLORS, the backend sends it as a string)
export type NoteResponse = Schemas['NoteResponse'];
export type FileResponse = Schemas['FileResponse'];
export type DiscoverTextResponse = Schemas['DiscoverTextResponse'];

// users
export type User = Schemas['UserResponse'];

// redirects
export type RedirectTarget = Schemas['RedirectTarget'];
export type Redirect = Schemas['RedirectResponse'];

// static hosting
export type Bucket = Schemas['BucketResponse'];
export type BucketSecret = Schemas['BucketSecretResponse'];
export type AccessLogs = Schemas['AccessLogsResponse'];
export type SiteMetrics = Schemas['SiteMetricsResponse'];

// health (the state is the name of HealthState of the backend, sent as a string)
export type HealthState = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN';
export type HealthDetail = Omit<Schemas['HealthDetailResponse'], 'state'> & { state: HealthState };
export type HealthStatus = Omit<Schemas['HealthStatusResponse'], 'state' | 'details'> & {
  state: HealthState;
  details: HealthDetail[];
};

// settings: the default is a JsonPrimitive of the backend, a boolean or a text, which the schema
// writes as a string; requires is null without a requirement, which the schema does not say
export type SettingValue = boolean | string;
export type SettingRequirement = Schemas['SettingRequirementResponse'];
export type SettingDefinition = Omit<Schemas['SettingResponse'], 'default' | 'requires'> & {
  default: SettingValue;
  requires?: SettingRequirement | null;
};
export type SettingScope = Omit<Schemas['SettingScopeResponse'], 'settings'> & {
  settings: SettingDefinition[];
};
