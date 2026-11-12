// the shapes of the api by readable names, from the types generated of openapi.json (schema.ts). A
// field the schema marks optional may be missing or null alike: the backend leaves out a nullable
// field with a default when it is null, so the apps test `== null`, never `=== null` alone
import { components } from './schema';

type Schemas = components['schemas'];

// a page of a list endpoint (the items differ by endpoint, the schema has no named page)
export type Pagination = Schemas['PaginationResponse'];

// auth
export type ExchangeResponse = Schemas['ExchangeResponse'];
export type WhoAmI = Schemas['WhoAmIResponse'];
// who signs in to the deployment: multi, users by e-mail and the admin by password; single, only
// the admin by password
export type DeploymentInfo = Schemas['InfoResponse'];
export type UserMode = DeploymentInfo['userMode'];
// the QR code that connects an app (SECURITY-3): its link holds the token, never shown or logged
export type Connect = Schemas['ConnectResponse'];
export type ConnectStatus = Schemas['ConnectStatusResponse'];

// grocery
export type GroceryCategory = Schemas['GroceryCategoryResponse'];
export type GroceryList = Schemas['GroceryListResponse'];
export type GroceryItem = Schemas['GroceryItemResponse'];
export type GroceryNewItem = Schemas['GroceryNewItemRequest'];
export type GrocerySuggestion = Schemas['GrocerySuggestionResponse'];

// todo; the schema has the priority of a task as one of its strings already (TODO-5)
export type TodoList = Schemas['TodoListResponse'];
export type TodoItem = Schemas['TodoItemResponse'];
export type TodoPriority = TodoItem['priority'];

// notes; what the notes app may do for the signed in user
export type NoteResponse = Schemas['NoteResponse'];
export type NotesSettings = Schemas['NotesSettingsResponse'];
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

// health
export type HealthStatus = Schemas['HealthStatusResponse'];
export type HealthDetail = Schemas['HealthDetailResponse'];
export type HealthState = HealthStatus['state'];

// settings: the default is any primitive of the backend (its schema corrected in SakApiDoc), the
// settings are a boolean or a text
export type SettingValue = boolean | string;
export type SettingRequirement = Schemas['SettingRequirementResponse'];
export type SettingDefinition = Schemas['SettingResponse'];
export type SettingScope = Schemas['SettingScopeResponse'];
