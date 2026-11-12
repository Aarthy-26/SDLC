/*
Story ID: 118124
Title: UI: Base URL groups UI integration
Requires Node 18+ / TypeScript 5+

What this file does:
Implements a standalone TypeScript model/controller-style utility for Base URL group UI integration logic.
It covers listing groups, inline backend validation mapping for unique/required names, active Base URL switching with confirmation data,
no-active warnings, deletion blocking when APIs reference a Base URL, masking of sensitive authentication values, unchanged placeholders,
and screen state shaping for loading/empty/error UI scenarios.

Assumptions:
- Repo content was not provided, so this is a standalone TypeScript implementation.
- Backend/API calls are represented through typed in-memory data and response-mapping methods so the file remains self-contained and runnable.
- The most recent available work item content contained fields, description, and acceptance criteria; comments/attachments/linked items were not returned.

Compile:
  tsc 118124.ts
Run:
  node 118124.js
*/

type Identifier = string;
type ScreenState = 'loading' | 'ready' | 'empty' | 'error';
type BadgeStatus = 'Active' | 'Inactive';
type AuthFieldMode = 'masked' | 'unchanged-placeholder' | 'plain-input';

interface ValidationError {
  field: string;
  message: string;
}

interface ApiErrorPayload {
  status: number;
  message: string;
  details?: string[];
}

interface HeaderEntry {
  key: string;
  value: string;
}

interface QueryParameterEntry {
  key: string;
  value: string;
}

interface AuthenticationSecret {
  key: string;
  maskedValue: string;
  mode: AuthFieldMode;
}

interface AuthenticationConfig {
  type: 'none' | 'basic' | 'bearer' | 'apiKey' | 'oauth2' | 'custom';
  secrets: AuthenticationSecret[];
}

interface BaseUrlRecord {
  id: Identifier;
  groupId: Identifier;
  name: string;
  baseUrl: string;
  authentication: AuthenticationConfig;
  headers: HeaderEntry[];
  queryParameters: QueryParameterEntry[];
  isActive: boolean;
}

interface BaseUrlGroupRecord {
  id: Identifier;
  ownerUserId: Identifier;
  name: string;
}

interface ReferencingApiRecord {
  apiId: Identifier;
  apiName: string;
  baseUrlId: Identifier;
}

interface UserContext {
  userId: Identifier;
}

interface GroupListItemViewModel {
  id: Identifier;
  name: string;
  warning?: string;
  baseUrls: BaseUrlListItemViewModel[];
}

interface BaseUrlListItemViewModel {
  id: Identifier;
  name: string;
  baseUrl: string;
  status: BadgeStatus;
  authentication: AuthenticationConfig;
  headers: HeaderEntry[];
  queryParameters: QueryParameterEntry[];
}

interface GroupListScreenViewModel {
  state: ScreenState;
  groups: GroupListItemViewModel[];
  errorMessage?: string;
}

interface ConfirmActivationResult {
  requiresConfirmation: boolean;
  message?: string;
  targetBaseUrlId: Identifier;
  targetBaseUrlName: string;
  willDeactivateBaseUrlId?: Identifier;
  willDeactivateBaseUrlName?: string;
}

interface OperationResult<T> {
  success: boolean;
  data?: T;
  error?: string;
  validationErrors?: ValidationError[];
}

interface CreateGroupInput {
  name: string;
}

interface CreateBaseUrlInput {
  name: string;
  baseUrl: string;
  authentication: AuthenticationConfig;
  headers: HeaderEntry[];
  queryParameters: QueryParameterEntry[];
  isActive: boolean;
}

class BaseUrlGroupsUiIntegration {
  private readonly unchangedPlaceholder: string = 'unchanged';
  private readonly groups: BaseUrlGroupRecord[] = [];
  private readonly baseUrls: BaseUrlRecord[] = [];
  private readonly apiReferences: ReferencingApiRecord[] = [];

  public constructor() {
    const userId: Identifier = 'user-1';
    const otherUserId: Identifier = 'user-2';

    this.groups.push(
      { id: 'group-1', ownerUserId: userId, name: 'DEV / QA / PROD' },
      { id: 'group-2', ownerUserId: userId, name: 'Sandbox' },
      { id: 'group-3', ownerUserId: otherUserId, name: 'Other User Group' },
    );

    this.baseUrls.push(
      {
        id: 'base-1',
        groupId: 'group-1',
        name: 'DEV Endpoint',
        baseUrl: 'https://dev.example.com',
        authentication: {
          type: 'bearer',
          secrets: [{ key: 'token', maskedValue: '********', mode: 'masked' }],
        },
        headers: [{ key: 'x-env', value: 'dev' }],
        queryParameters: [{ key: 'version', value: '1' }],
        isActive: true,
      },
      {
        id: 'base-2',
        groupId: 'group-1',
        name: 'QA Endpoint',
        baseUrl: 'https://qa.example.com',
        authentication: {
          type: 'apiKey',
          secrets: [{ key: 'apiKey', maskedValue: '********', mode: 'masked' }],
        },
        headers: [{ key: 'x-env', value: 'qa' }],
        queryParameters: [],
        isActive: false,
      },
      {
        id: 'base-3',
        groupId: 'group-2',
        name: 'Sandbox Endpoint',
        baseUrl: 'https://sandbox.example.com',
        authentication: {
          type: 'none',
          secrets: [],
        },
        headers: [],
        queryParameters: [],
        isActive: false,
      },
      {
        id: 'base-4',
        groupId: 'group-3',
        name: 'Private Endpoint',
        baseUrl: 'https://private.example.com',
        authentication: {
          type: 'basic',
          secrets: [
            { key: 'username', maskedValue: '********', mode: 'masked' },
            { key: 'password', maskedValue: '********', mode: 'masked' },
          ],
        },
        headers: [],
        queryParameters: [],
        isActive: true,
      },
    );

    this.apiReferences.push(
      { apiId: 'api-1', apiName: 'Customer Search API', baseUrlId: 'base-1' },
      { apiId: 'api-2', apiName: 'Order Sync API', baseUrlId: 'base-1' },
    );
  }

  public getGroupsScreen(user: UserContext): GroupListScreenViewModel {
    const ownedGroups: BaseUrlGroupRecord[] = this.groups.filter((group) => group.ownerUserId === user.userId);

    if (ownedGroups.length === 0) {
      return {
        state: 'empty',
        groups: [],
      };
    }

    const groups: GroupListItemViewModel[] = ownedGroups.map((group) => {
      const groupBaseUrls: BaseUrlRecord[] = this.baseUrls.filter((item) => item.groupId === group.id);
      const activeExists: boolean = groupBaseUrls.some((item) => item.isActive);

      return {
        id: group.id,
        name: group.name,
        warning: activeExists ? undefined : 'No active Base URL',
        baseUrls: groupBaseUrls.map((item) => this.toBaseUrlListItem(item)),
      };
    });

    return {
      state: 'ready',
      groups,
    };
  }

  public createGroup(user: UserContext, input: CreateGroupInput): OperationResult<GroupListItemViewModel> {
    const name: string = input.name.trim();

    if (name.length === 0) {
      return {
        success: false,
        validationErrors: [{ field: 'name', message: 'Group name is required.' }],
      };
    }

    const duplicate: BaseUrlGroupRecord | undefined = this.groups.find(
      (group) => group.ownerUserId === user.userId && group.name.toLowerCase() === name.toLowerCase(),
    );

    if (duplicate !== undefined) {
      return {
        success: false,
        validationErrors: [{ field: 'name', message: 'Group name must be unique.' }],
      };
    }

    const group: BaseUrlGroupRecord = {
      id: this.nextId('group'),
      ownerUserId: user.userId,
      name,
    };

    this.groups.push(group);

    return {
      success: true,
      data: {
        id: group.id,
        name: group.name,
        warning: 'No active Base URL',
        baseUrls: [],
      },
    };
  }

  public addBaseUrl(
    user: UserContext,
    groupId: Identifier,
    input: CreateBaseUrlInput,
  ): OperationResult<GroupListItemViewModel> {
    const group: BaseUrlGroupRecord | undefined = this.findOwnedGroup(user, groupId);

    if (group === undefined) {
      return this.forbiddenOrMissingGroup();
    }

    const newBaseUrl: BaseUrlRecord = {
      id: this.nextId('base'),
      groupId,
      name: input.name.trim(),
      baseUrl: input.baseUrl.trim(),
      authentication: this.maskAuthenticationForStorage(input.authentication),
      headers: input.headers.map((header) => ({ ...header })),
      queryParameters: input.queryParameters.map((parameter) => ({ ...parameter })),
      isActive: false,
    };

    if (input.isActive) {
      this.deactivateAllInGroup(groupId);
      newBaseUrl.isActive = true;
    }

    this.baseUrls.push(newBaseUrl);

    return {
      success: true,
      data: this.getSingleGroupViewModel(groupId),
    };
  }

  public prepareActivation(user: UserContext, baseUrlId: Identifier): OperationResult<ConfirmActivationResult> {
    const baseUrl: BaseUrlRecord | undefined = this.findOwnedBaseUrl(user, baseUrlId);

    if (baseUrl === undefined) {
      return this.forbiddenOrMissingBaseUrl();
    }

    const currentActive: BaseUrlRecord | undefined = this.baseUrls.find(
      (item) => item.groupId === baseUrl.groupId && item.isActive && item.id !== baseUrl.id,
    );

    if (currentActive === undefined) {
      return {
        success: true,
        data: {
          requiresConfirmation: false,
          targetBaseUrlId: baseUrl.id,
          targetBaseUrlName: baseUrl.name,
        },
      };
    }

    return {
      success: true,
      data: {
        requiresConfirmation: true,
        message: `${currentActive.name} will become inactive.`,
        targetBaseUrlId: baseUrl.id,
        targetBaseUrlName: baseUrl.name,
        willDeactivateBaseUrlId: currentActive.id,
        willDeactivateBaseUrlName: currentActive.name,
      },
    };
  }

  public activateBaseUrl(user: UserContext, baseUrlId: Identifier): OperationResult<GroupListItemViewModel> {
    const baseUrl: BaseUrlRecord | undefined = this.findOwnedBaseUrl(user, baseUrlId);

    if (baseUrl === undefined) {
      return this.forbiddenOrMissingBaseUrl();
    }

    this.deactivateAllInGroup(baseUrl.groupId);
    baseUrl.isActive = true;

    return {
      success: true,
      data: this.getSingleGroupViewModel(baseUrl.groupId),
    };
  }

  public deactivateBaseUrl(user: UserContext, baseUrlId: Identifier): OperationResult<GroupListItemViewModel> {
    const baseUrl: BaseUrlRecord | undefined = this.findOwnedBaseUrl(user, baseUrlId);

    if (baseUrl === undefined) {
      return this.forbiddenOrMissingBaseUrl();
    }

    baseUrl.isActive = false;

    return {
      success: true,
      data: this.getSingleGroupViewModel(baseUrl.groupId),
    };
  }

  public deleteBaseUrl(user: UserContext, baseUrlId: Identifier): OperationResult<GroupListItemViewModel> {
    const baseUrl: BaseUrlRecord | undefined = this.findOwnedBaseUrl(user, baseUrlId);

    if (baseUrl === undefined) {
      return this.forbiddenOrMissingBaseUrl();
    }

    const references: ReferencingApiRecord[] = this.apiReferences.filter((reference) => reference.baseUrlId === baseUrlId);
    if (references.length > 0) {
      return {
        success: false,
        error: `Deleting this Base URL is blocked because it is referenced by: ${references
          .map((reference) => reference.apiName)
          .join(', ')}`,
      };
    }

    const index: number = this.baseUrls.findIndex((item) => item.id === baseUrlId);
    if (index >= 0) {
      this.baseUrls.splice(index, 1);
    }

    return {
      success: true,
      data: this.getSingleGroupViewModel(baseUrl.groupId),
    };
  }

  public getApiConfigurationGroupOptions(user: UserContext): OperationResult<Array<{ id: Identifier; name: string }>> {
    const options: Array<{ id: Identifier; name: string }> = this.groups
      .filter((group) => group.ownerUserId === user.userId)
      .map((group) => ({ id: group.id, name: group.name }));

    return {
      success: true,
      data: options,
    };
  }

  public shapeApiErrorForUi(error: ApiErrorPayload): string {
    if (error.status === 403) {
      return 'You do not have access to this Base URL group or Base URL.';
    }

    if (error.status === 404) {
      return 'The requested Base URL group or Base URL was not found.';
    }

    if (error.details !== undefined && error.details.length > 0) {
      return `${error.message}: ${error.details.join('; ')}`;
    }

    return error.message;
  }

  public getLoadingState(): GroupListScreenViewModel {
    return {
      state: 'loading',
      groups: [],
    };
  }

  public getErrorState(message: string): GroupListScreenViewModel {
    return {
      state: 'error',
      groups: [],
      errorMessage: message,
    };
  }

  public getEditAuthenticationView(user: UserContext, baseUrlId: Identifier): OperationResult<AuthenticationConfig> {
    const baseUrl: BaseUrlRecord | undefined = this.findOwnedBaseUrl(user, baseUrlId);

    if (baseUrl === undefined) {
      return this.forbiddenOrMissingBaseUrl();
    }

    return {
      success: true,
      data: {
        type: baseUrl.authentication.type,
        secrets: baseUrl.authentication.secrets.map((secret) => ({
          key: secret.key,
          maskedValue: this.unchangedPlaceholder,
          mode: 'unchanged-placeholder',
        })),
      },
    };
  }

  private getSingleGroupViewModel(groupId: Identifier): GroupListItemViewModel {
    const group: BaseUrlGroupRecord = this.groups.find((item) => item.id === groupId) as BaseUrlGroupRecord;
    const groupBaseUrls: BaseUrlRecord[] = this.baseUrls.filter((item) => item.groupId === groupId);
    const activeExists: boolean = groupBaseUrls.some((item) => item.isActive);

    return {
      id: group.id,
      name: group.name,
      warning: activeExists ? undefined : 'No active Base URL',
      baseUrls: groupBaseUrls.map((item) => this.toBaseUrlListItem(item)),
    };
  }

  private toBaseUrlListItem(item: BaseUrlRecord): BaseUrlListItemViewModel {
    return {
      id: item.id,
      name: item.name,
      baseUrl: item.baseUrl,
      status: item.isActive ? 'Active' : 'Inactive',
      authentication: {
        type: item.authentication.type,
        secrets: item.authentication.secrets.map((secret) => ({ ...secret, maskedValue: '********', mode: 'masked' })),
      },
      headers: item.headers.map((header) => ({ ...header })),
      queryParameters: item.queryParameters.map((parameter) => ({ ...parameter })),
    };
  }

  private maskAuthenticationForStorage(authentication: AuthenticationConfig): AuthenticationConfig {
    return {
      type: authentication.type,
      secrets: authentication.secrets.map((secret) => ({
        key: secret.key,
        maskedValue: secret.maskedValue.length === 0 ? '********' : '********',
        mode: 'masked',
      })),
    };
  }

  private deactivateAllInGroup(groupId: Identifier): void {
    for (const item of this.baseUrls) {
      if (item.groupId === groupId) {
        item.isActive = false;
      }
    }
  }

  private findOwnedGroup(user: UserContext, groupId: Identifier): BaseUrlGroupRecord | undefined {
    return this.groups.find((group) => group.id === groupId && group.ownerUserId === user.userId);
  }

  private findOwnedBaseUrl(user: UserContext, baseUrlId: Identifier): BaseUrlRecord | undefined {
    const candidate: BaseUrlRecord | undefined = this.baseUrls.find((item) => item.id === baseUrlId);
    if (candidate === undefined) {
      return undefined;
    }

    const group: BaseUrlGroupRecord | undefined = this.findOwnedGroup(user, candidate.groupId);
    return group === undefined ? undefined : candidate;
  }

  private forbiddenOrMissingGroup(): OperationResult<never> {
    return {
      success: false,
      error: this.shapeApiErrorForUi({ status: 404, message: 'Group not found' }),
    };
  }

  private forbiddenOrMissingBaseUrl(): OperationResult<never> {
    return {
      success: false,
      error: this.shapeApiErrorForUi({ status: 404, message: 'Base URL not found' }),
    };
  }

  private nextId(prefix: string): Identifier {
    const randomPart: string = Math.random().toString(36).slice(2, 10);
    return `${prefix}-${randomPart}`;
  }
}
