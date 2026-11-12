/*
Story ID: 118124
Title: UI: Base URL groups UI integration
Requires Node 18+ / TypeScript 5+

What this code does:
Implements a standalone TypeScript model/controller layer for managing Base URL groups in a UI-facing way.
It supports listing groups, creating unique groups, adding Base URLs with masked sensitive fields, enforcing exactly one active Base URL per group when requested, deactivating the active Base URL, selecting a group for API configuration, and blocking deletion when referenced APIs exist.

Assumptions:
- Repo content was not provided, so this is a standalone implementation.
- "Inline backend validation message" is represented as returned validation errors from methods.
- Sensitive authentication/header/query values are never returned in plaintext once stored; read operations expose only masked or unchanged placeholder values.
- 403/404 and loading/empty/error UI states are represented through typed view-state responses suitable for a UI layer.

Compile:
  tsc 118124.ts
Run:
  node 118124.js
*/

class BaseUrlGroupsUiIntegration {
  private static readonly UNCHANGED_PLACEHOLDER = '•••• unchanged';
  private static readonly MASK = '••••';

  private readonly groups = new Map<string, BaseUrlGroupsUiIntegration.Group>();
  private readonly apiConfigurations = new Map<string, BaseUrlGroupsUiIntegration.ApiConfiguration>();

  public listGroupsForUser(userId: string): BaseUrlGroupsUiIntegration.GroupsScreenState {
    const ownedGroups = Array.from(this.groups.values())
      .filter((group) => group.ownerUserId === userId)
      .map((group) => this.toGroupView(group));

    if (ownedGroups.length === 0) {
      return {
        status: 'empty',
        groups: [],
        message: 'No Base URL groups found.'
      };
    }

    return {
      status: 'ready',
      groups: ownedGroups
    };
  }

  public createGroup(input: BaseUrlGroupsUiIntegration.CreateGroupInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const normalizedName = input.name.trim();

    if (normalizedName.length === 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION',
          message: 'Group name is required.',
          field: 'name'
        }
      };
    }

    const duplicate = Array.from(this.groups.values()).find(
      (group) => group.ownerUserId === input.userId && group.name.toLowerCase() === normalizedName.toLowerCase()
    );

    if (duplicate) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION',
          message: 'A Base URL group with this name already exists.',
          field: 'name'
        }
      };
    }

    const group: BaseUrlGroupsUiIntegration.Group = {
      id: this.generateId('group'),
      ownerUserId: input.userId,
      name: normalizedName,
      baseUrls: [],
      selectedForApis: []
    };

    this.groups.set(group.id, group);

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public addBaseUrl(input: BaseUrlGroupsUiIntegration.AddBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const group = this.groups.get(input.groupId);

    if (!group) {
      return this.notFound('Base URL group was not found.');
    }

    if (group.ownerUserId !== input.userId) {
      return this.forbidden('You can only modify your own Base URL groups.');
    }

    const baseUrlValue = input.baseUrl.trim();
    if (baseUrlValue.length === 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION',
          message: 'Base URL is required.',
          field: 'baseUrl'
        }
      };
    }

    const newBaseUrl: BaseUrlGroupsUiIntegration.BaseUrlRecord = {
      id: this.generateId('baseurl'),
      baseUrl: baseUrlValue,
      authentication: this.storeSecretEntries(input.authentication ?? []),
      headers: this.storeSecretEntries(input.headers ?? []),
      queryParameters: this.storeSecretEntries(input.queryParameters ?? []),
      isActive: false
    };

    if (input.activate === true) {
      group.baseUrls.forEach((item) => {
        item.isActive = false;
      });
      newBaseUrl.isActive = true;
    }

    group.baseUrls.push(newBaseUrl);

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public getActivationImpact(input: BaseUrlGroupsUiIntegration.UserScopedBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.ActivationImpact> {
    const groupResult = this.getOwnedGroupWithBaseUrl(input.userId, input.groupId, input.baseUrlId);
    if (!groupResult.ok) {
      return groupResult;
    }

    const { group, baseUrl } = groupResult.data;
    const currentlyActive = group.baseUrls.find((item) => item.isActive && item.id !== baseUrl.id);

    return {
      ok: true,
      data: {
        groupId: group.id,
        targetBaseUrlId: baseUrl.id,
        targetBaseUrl: baseUrl.baseUrl,
        willDeactivateBaseUrlId: currentlyActive?.id ?? null,
        willDeactivateBaseUrl: currentlyActive?.baseUrl ?? null,
        confirmationMessage: currentlyActive
          ? `Activating ${baseUrl.baseUrl} will deactivate ${currentlyActive.baseUrl}.`
          : `Activating ${baseUrl.baseUrl} will make it the only active Base URL in this group.`
      }
    };
  }

  public activateBaseUrl(input: BaseUrlGroupsUiIntegration.UserScopedBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const groupResult = this.getOwnedGroupWithBaseUrl(input.userId, input.groupId, input.baseUrlId);
    if (!groupResult.ok) {
      return groupResult;
    }

    const { group, baseUrl } = groupResult.data;
    group.baseUrls.forEach((item) => {
      item.isActive = item.id === baseUrl.id;
    });

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public deactivateBaseUrl(input: BaseUrlGroupsUiIntegration.UserScopedBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const groupResult = this.getOwnedGroupWithBaseUrl(input.userId, input.groupId, input.baseUrlId);
    if (!groupResult.ok) {
      return groupResult;
    }

    const { group, baseUrl } = groupResult.data;
    baseUrl.isActive = false;

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public deleteBaseUrl(input: BaseUrlGroupsUiIntegration.UserScopedBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const groupResult = this.getOwnedGroupWithBaseUrl(input.userId, input.groupId, input.baseUrlId);
    if (!groupResult.ok) {
      return groupResult;
    }

    const { group, baseUrl } = groupResult.data;
    const referencingApis = Array.from(this.apiConfigurations.values())
      .filter((api) => api.ownerUserId === input.userId && api.groupId === group.id && api.baseUrlId === baseUrl.id)
      .map((api) => api.apiName);

    if (referencingApis.length > 0) {
      return {
        ok: false,
        error: {
          code: 'CONFLICT',
          message: `Cannot delete Base URL. Referenced by APIs: ${referencingApis.join(', ')}.`
        }
      };
    }

    group.baseUrls = group.baseUrls.filter((item) => item.id !== baseUrl.id);

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public selectGroupForApi(input: BaseUrlGroupsUiIntegration.SelectGroupForApiInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.ApiConfigurationView> {
    const group = this.groups.get(input.groupId);

    if (!group) {
      return this.notFound('Base URL group was not found.');
    }

    if (group.ownerUserId !== input.userId) {
      return this.forbidden('You can only select your own Base URL groups.');
    }

    const activeBaseUrl = group.baseUrls.find((item) => item.isActive) ?? null;
    const apiConfiguration: BaseUrlGroupsUiIntegration.ApiConfiguration = {
      id: this.generateId('api'),
      ownerUserId: input.userId,
      apiName: input.apiName.trim(),
      groupId: group.id,
      baseUrlId: activeBaseUrl?.id ?? null
    };

    this.apiConfigurations.set(apiConfiguration.id, apiConfiguration);
    group.selectedForApis.push(apiConfiguration.id);

    return {
      ok: true,
      data: {
        id: apiConfiguration.id,
        apiName: apiConfiguration.apiName,
        groupId: group.id,
        groupName: group.name,
        activeBaseUrl: activeBaseUrl?.baseUrl ?? null,
        warning: activeBaseUrl === null ? 'No active Base URL' : null
      }
    };
  }

  public editBaseUrl(input: BaseUrlGroupsUiIntegration.EditBaseUrlInput): BaseUrlGroupsUiIntegration.ActionResult<BaseUrlGroupsUiIntegration.GroupView> {
    const groupResult = this.getOwnedGroupWithBaseUrl(input.userId, input.groupId, input.baseUrlId);
    if (!groupResult.ok) {
      return groupResult;
    }

    const { group, baseUrl } = groupResult.data;

    if (typeof input.baseUrl === 'string') {
      const trimmed = input.baseUrl.trim();
      if (trimmed.length === 0) {
        return {
          ok: false,
          error: {
            code: 'VALIDATION',
            message: 'Base URL is required.',
            field: 'baseUrl'
          }
        };
      }
      baseUrl.baseUrl = trimmed;
    }

    if (input.authentication) {
      baseUrl.authentication = this.applySecretEntryUpdates(baseUrl.authentication, input.authentication);
    }

    if (input.headers) {
      baseUrl.headers = this.applySecretEntryUpdates(baseUrl.headers, input.headers);
    }

    if (input.queryParameters) {
      baseUrl.queryParameters = this.applySecretEntryUpdates(baseUrl.queryParameters, input.queryParameters);
    }

    return {
      ok: true,
      data: this.toGroupView(group)
    };
  }

  public getGroupScreen(input: BaseUrlGroupsUiIntegration.UserScopedGroupInput): BaseUrlGroupsUiIntegration.GroupScreenState {
    const group = this.groups.get(input.groupId);

    if (!group) {
      return {
        status: 'error',
        error: {
          code: 'NOT_FOUND',
          message: 'Base URL group was not found.'
        }
      };
    }

    if (group.ownerUserId !== input.userId) {
      return {
        status: 'error',
        error: {
          code: 'FORBIDDEN',
          message: 'You can only view your own Base URL groups.'
        }
      };
    }

    return {
      status: 'ready',
      group: this.toGroupView(group)
    };
  }

  private getOwnedGroupWithBaseUrl(
    userId: string,
    groupId: string,
    baseUrlId: string
  ): BaseUrlGroupsUiIntegration.ActionResult<{ group: BaseUrlGroupsUiIntegration.Group; baseUrl: BaseUrlGroupsUiIntegration.BaseUrlRecord }> {
    const group = this.groups.get(groupId);

    if (!group) {
      return this.notFound('Base URL group was not found.');
    }

    if (group.ownerUserId !== userId) {
      return this.forbidden('You can only modify your own Base URL groups.');
    }

    const baseUrl = group.baseUrls.find((item) => item.id === baseUrlId);
    if (!baseUrl) {
      return this.notFound('Base URL was not found.');
    }

    return {
      ok: true,
      data: { group, baseUrl }
    };
  }

  private toGroupView(group: BaseUrlGroupsUiIntegration.Group): BaseUrlGroupsUiIntegration.GroupView {
    const activeCount = group.baseUrls.filter((item) => item.isActive).length;

    return {
      id: group.id,
      name: group.name,
      warning: activeCount === 0 ? 'No active Base URL' : null,
      baseUrls: group.baseUrls.map((item) => ({
        id: item.id,
        baseUrl: item.baseUrl,
        status: item.isActive ? 'Active' : 'Inactive',
        isActive: item.isActive,
        authentication: this.maskSecretEntries(item.authentication),
        headers: this.maskSecretEntries(item.headers),
        queryParameters: this.maskSecretEntries(item.queryParameters)
      }))
    };
  }

  private storeSecretEntries(entries: ReadonlyArray<BaseUrlGroupsUiIntegration.SecretEntryInput>): BaseUrlGroupsUiIntegration.SecretEntry[] {
    return entries.map((entry) => ({
      key: entry.key.trim(),
      value: entry.value,
      secret: entry.secret
    }));
  }

  private applySecretEntryUpdates(
    existing: ReadonlyArray<BaseUrlGroupsUiIntegration.SecretEntry>,
    updates: ReadonlyArray<BaseUrlGroupsUiIntegration.SecretEntryUpdateInput>
  ): BaseUrlGroupsUiIntegration.SecretEntry[] {
    return updates.map((entry) => {
      const previous = existing.find((item) => item.key === entry.key.trim());
      const unchanged = entry.value === BaseUrlGroupsUiIntegration.UNCHANGED_PLACEHOLDER;

      return {
        key: entry.key.trim(),
        value: unchanged && previous ? previous.value : entry.value,
        secret: entry.secret
      };
    });
  }

  private maskSecretEntries(entries: ReadonlyArray<BaseUrlGroupsUiIntegration.SecretEntry>): BaseUrlGroupsUiIntegration.MaskedSecretEntry[] {
    return entries.map((entry) => ({
      key: entry.key,
      value: entry.secret ? BaseUrlGroupsUiIntegration.MASK : entry.value,
      displayValue: entry.secret ? BaseUrlGroupsUiIntegration.UNCHANGED_PLACEHOLDER : entry.value,
      secret: entry.secret
    }));
  }

  private generateId(prefix: string): string {
    const random = Math.random().toString(36).slice(2, 10);
    return `${prefix}_${random}`;
  }

  private notFound<T>(message: string): BaseUrlGroupsUiIntegration.ActionResult<T> {
    return {
      ok: false,
      error: {
        code: 'NOT_FOUND',
        message
      }
    };
  }

  private forbidden<T>(message: string): BaseUrlGroupsUiIntegration.ActionResult<T> {
    return {
      ok: false,
      error: {
        code: 'FORBIDDEN',
        message
      }
    };
  }
}

namespace BaseUrlGroupsUiIntegration {
  export interface Group {
    id: string;
    ownerUserId: string;
    name: string;
    baseUrls: BaseUrlRecord[];
    selectedForApis: string[];
  }

  export interface BaseUrlRecord {
    id: string;
    baseUrl: string;
    authentication: SecretEntry[];
    headers: SecretEntry[];
    queryParameters: SecretEntry[];
    isActive: boolean;
  }

  export interface SecretEntry {
    key: string;
    value: string;
    secret: boolean;
  }

  export interface MaskedSecretEntry {
    key: string;
    value: string;
    displayValue: string;
    secret: boolean;
  }

  export interface ApiConfiguration {
    id: string;
    ownerUserId: string;
    apiName: string;
    groupId: string;
    baseUrlId: string | null;
  }

  export interface CreateGroupInput {
    userId: string;
    name: string;
  }

  export interface SecretEntryInput {
    key: string;
    value: string;
    secret: boolean;
  }

  export interface SecretEntryUpdateInput {
    key: string;
    value: string;
    secret: boolean;
  }

  export interface AddBaseUrlInput {
    userId: string;
    groupId: string;
    baseUrl: string;
    authentication?: SecretEntryInput[];
    headers?: SecretEntryInput[];
    queryParameters?: SecretEntryInput[];
    activate?: boolean;
  }

  export interface EditBaseUrlInput {
    userId: string;
    groupId: string;
    baseUrlId: string;
    baseUrl?: string;
    authentication?: SecretEntryUpdateInput[];
    headers?: SecretEntryUpdateInput[];
    queryParameters?: SecretEntryUpdateInput[];
  }

  export interface UserScopedGroupInput {
    userId: string;
    groupId: string;
  }

  export interface UserScopedBaseUrlInput extends UserScopedGroupInput {
    baseUrlId: string;
  }

  export interface SelectGroupForApi {
    userId: string;
    apiName: string;
    groupId: string;
  }

  export type SelectGroupForApiInput = SelectGroupForApi;

  export interface GroupView {
    id: string;
    name: string;
    warning: string | null;
    baseUrls: BaseUrlView[];
  }

  export interface BaseUrlView {
    id: string;
    baseUrl: string;
    status: 'Active' | 'Inactive';
    isActive: boolean;
    authentication: MaskedSecretEntry[];
    headers: MaskedSecretEntry[];
    queryParameters: MaskedSecretEntry[];
  }

  export interface ApiConfigurationView {
    id: string;
    apiName: string;
    groupId: string;
    groupName: string;
    activeBaseUrl: string | null;
    warning: string | null;
  }

  export interface ActivationImpact {
    groupId: string;
    targetBaseUrlId: string;
    targetBaseUrl: string;
    willDeactivateBaseUrlId: string | null;
    willDeactivateBaseUrl: string | null;
    confirmationMessage: string;
  }

  export type ErrorCode = 'VALIDATION' | 'FORBIDDEN' | 'NOT_FOUND' | 'CONFLICT';

  export interface ActionError {
    code: ErrorCode;
    message: string;
    field?: string;
  }

  export type ActionResult<T> =
    | { ok: true; data: T }
    | { ok: false; error: ActionError };

  export type ViewStatus = 'loading' | 'ready' | 'empty' | 'error';

  export interface GroupsScreenState {
    status: Exclude<ViewStatus, 'loading'>;
    groups: GroupView[];
    message?: string;
    error?: ActionError;
  }

  export type GroupScreenState =
    | { status: 'ready'; group: GroupView }
    | { status: 'error'; error: ActionError };
}
