Detected Language: TypeScript | Test Framework: Jest (ts-jest)
| id | description | scenario type | expected output |
|---|---|---|---|
| 1 | Returns a report with no errors or warnings for a fully configured valid application | happy path | `issues` is empty; summary flags for pages/navigation, bindings, api-query, workflow, and persistence are `true` |
| 2 | Adds an error when `applicationName` is blank after trimming | edge case | Issue code `APP_NAME_MISSING` |
| 3 | Adds an error when no pages are supplied | edge case | Issue code `NO_PAGES` |
| 4 | Adds a warning when no page is marked as home | edge case | Issue code `HOME_PAGE_MISSING` |
| 5 | Adds a warning when multiple pages are marked as home | edge case | Issue code `MULTIPLE_HOME_PAGES` |
| 6 | Adds an error when a page name is blank | negative | Issue code `PAGE_NAME_MISSING` |
| 7 | Adds a warning when a page has no widgets | edge case | Issue code `EMPTY_PAGE` |
| 8 | Adds a warning for a widget with a blank name | negative | Issue code `WIDGET_NAME_MISSING` |
| 9 | Adds a warning for an unsupported widget type | negative | Issue code `UNSUPPORTED_WIDGET_TYPE` |
| 10 | Adds a warning when a required input widget has no value/default/binding | validation | Issue code `REQUIRED_WIDGET_NOT_CONFIGURED` |
| 11 | Extracts bindings from widget props and raises malformed binding errors | transformation-level | Issue code `MALFORMED_BINDING` |
| 12 | Raises binding path invalid error for incomplete or double-dot moustache paths | negative | Issue code `BINDING_PATH_INVALID` |
| 13 | Adds info issue for an inactive action handler | edge case | Issue code `INACTIVE_ACTION_HANDLER` |
| 14 | Raises missing query/api/workflow reference errors for handler targets not found in registries | negative | Issue codes `QUERY_REFERENCE_NOT_FOUND`, `API_REFERENCE_NOT_FOUND`, and `WORKFLOW_REFERENCE_NOT_FOUND` |
| 15 | Raises broken navigation path when a navigation widget points to a page id not present in the page set | navigation | Issue code `BROKEN_NAVIGATION_PATH` |
| 16 | Reveals the current page-scoped navigation validation bug for valid cross-page navigation | negative / bug exposure | Issue code `NAVIGATION_TARGET_NOT_FOUND` is produced even when target page exists globally |
| 17 | Adds query validation issues for missing statement and missing datasource | validation | Issue codes `QUERY_STATEMENT_MISSING` and `QUERY_DATASOURCE_MISSING` |
| 18 | Adds a warning when query statement does not clearly indicate CRUD operation | transformation-level | Issue code `QUERY_OPERATION_UNCLEAR` |
| 19 | Adds API validation issues for missing method and missing URL/baseUrl | validation | Issue codes `API_METHOD_MISSING` and `API_URL_MISSING` |
| 20 | Adds workflow validation warnings for missing input and output schemas | validation | Issue codes `WORKFLOW_INPUT_MISSING` and `WORKFLOW_OUTPUT_MISSING` |
| 21 | Adds persistence warning when no persistence-related widget configuration exists | edge case | Issue code `NO_PERSISTENCE_CONFIGURATION` |
| 22 | Adds persistence error for remember-state widget with no storage key and warning for unregistered key | validation | Issue codes `PERSISTENCE_KEY_MISSING` and `PERSISTENCE_KEY_NOT_REGISTERED` |
| 23 | Adds arsenal validation warning/info for empty arsenal, missing agent status, and draft agent | validation | Issue codes `ARSENAL_EMPTY`, `AGENT_STATUS_MISSING`, and `AGENT_NOT_BUILT` |
| 24 | Builds summary counts and module booleans consistently when issues exist | aggregation | `failedChecks`/`warningChecks` match issue severities and affected summary booleans become `false` |
Unit Test Code:
```typescript
import { describe, expect, it, jest } from '@jest/globals';
import SculptAgEndToEndValidator, {
  type SculptAgValidationInput,
  type ValidationActionHandler,
  type ValidationApi,
  type ValidationArsenal,
  type ValidationPage,
  type ValidationQuery,
  type ValidationWidget,
  type ValidationWorkflow,
} from '../Code_Output_New/125546';

describe('SculptAgEndToEndValidator', () => {
  const fixedIso = '2026-01-01T00:00:00.000Z';

  afterEach(() => {
    jest.useRealTimers();
  });

  const createValidInput = (): SculptAgValidationInput => {
    const pages: ValidationPage[] = [
      {
        id: 'home',
        name: 'Home',
        isHome: true,
        components: [
          {
            id: 'input-1',
            name: 'Customer Name',
            type: 'Input',
            props: {
              required: true,
              defaultValue: 'Synthetic User',
              persistKey: 'customer.name',
              label: 'Customer',
            },
            bindings: {
              value: '{{Query1.data.value}}',
            },
            widgetActionHandlers: [
              {
                eventType: 'onClick',
                actionType: 'executeQuery',
                queryId: 'query-1',
                active: true,
              },
              {
                eventType: 'onSubmit',
                actionType: 'executeApi',
                apiId: 'api-1',
                active: true,
              },
              {
                eventType: 'onSuccess',
                actionType: 'executeWorkflow',
                workflowId: 'wf-1',
                active: true,
              },
            ],
          },
          {
            id: 'nav-1',
            name: 'Next',
            type: 'Button',
            props: {
              value: 'continue',
            },
            widgetActionHandlers: [
              {
                eventType: 'onClick',
                actionType: 'navigate',
                targetPageId: 'home',
                active: true,
              },
            ],
          },
        ],
      },
    ];

    const queries: ValidationQuery[] = [
      {
        id: 'query-1',
        name: 'LoadCustomers',
        datasourceId: 'ds-1',
        statement: 'SELECT * FROM customers',
      },
    ];

    const apis: ValidationApi[] = [
      {
        id: 'api-1',
        name: 'SaveCustomer',
        method: 'POST',
        url: 'https://example.test/customers',
      },
    ];

    const workflows: ValidationWorkflow[] = [
      {
        id: 'wf-1',
        name: 'ValidateCustomerWorkflow',
        inputSchema: { customerId: 'string' },
        outputSchema: { status: 'string' },
      },
    ];

    const arsenals: ValidationArsenal[] = [
      {
        id: 'ars-1',
        name: 'Primary Arsenal',
        agents: [
          {
            id: 'agent-1',
            name: 'Ready Agent',
            status: 'ready',
          },
        ],
      },
    ];

    return {
      applicationName: 'Sculpt Synthetic App',
      pages,
      queries,
      apis,
      workflows,
      arsenals,
      persistedKeys: ['customer.name'],
    };
  };

  const validateWithFrozenTime = (input: SculptAgValidationInput) => {
    jest.useFakeTimers().setSystemTime(new Date(fixedIso));
    return SculptAgEndToEndValidator.validate(input);
  };

  const issueCodes = (input: SculptAgValidationInput): string[] =>
    validateWithFrozenTime(input).issues.map((issue) => issue.code);

  it('returns a report with no errors or warnings for a fully configured valid application', () => {
    const report = validateWithFrozenTime(createValidInput());

    expect(report.generatedAt).toBe(fixedIso);
    expect(report.title).toBe('End-to-End Validation Report - Sculpt Synthetic App');
    expect(report.issues).toHaveLength(0);
    expect(report.summary.failedChecks).toBe(0);
    expect(report.summary.warningChecks).toBe(0);
    expect(report.summary.completeUserJourneyValidated).toBe(true);
    expect(report.summary.dataBindingValidated).toBe(true);
    expect(report.summary.apiAndQueryValidated).toBe(true);
    expect(report.summary.workflowValidated).toBe(true);
    expect(report.summary.persistenceValidated).toBe(true);
  });

  it('adds an error when applicationName is blank after trimming', () => {
    const input = createValidInput();
    input.applicationName = '   ';

    expect(issueCodes(input)).toContain('APP_NAME_MISSING');
  });

  it('adds an error when no pages are supplied', () => {
    const input = createValidInput();
    input.pages = [];

    expect(issueCodes(input)).toContain('NO_PAGES');
  });

  it('adds a warning when no page is marked as home', () => {
    const input = createValidInput();
    input.pages = input.pages.map((page) => ({ ...page, isHome: false }));

    expect(issueCodes(input)).toContain('HOME_PAGE_MISSING');
  });

  it('adds a warning when multiple pages are marked as home', () => {
    const input = createValidInput();
    input.pages = [
      ...input.pages,
      {
        id: 'secondary-home',
        name: 'Secondary Home',
        isHome: true,
        components: [
          {
            id: 'label-1',
            name: 'Secondary Widget',
            type: 'Table',
          },
        ],
      },
    ];

    expect(issueCodes(input)).toContain('MULTIPLE_HOME_PAGES');
  });

  it('adds an error when a page name is blank', () => {
    const input = createValidInput();
    input.pages[0] = { ...input.pages[0], name: '   ' };

    expect(issueCodes(input)).toContain('PAGE_NAME_MISSING');
  });

  it('adds a warning when a page has no widgets', () => {
    const input = createValidInput();
    input.pages[0] = { ...input.pages[0], components: [] };

    expect(issueCodes(input)).toContain('EMPTY_PAGE');
  });

  it('adds a warning for a widget with a blank name', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'widget-blank-name',
        name: '   ',
        type: 'Input',
        props: { defaultValue: 'x' },
      },
    ];

    expect(issueCodes(input)).toContain('WIDGET_NAME_MISSING');
  });

  it('adds a warning for an unsupported widget type', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'widget-custom',
        name: 'Unsupported Widget',
        type: 'TotallyCustom',
      },
    ];

    expect(issueCodes(input)).toContain('UNSUPPORTED_WIDGET_TYPE');
  });

  it('adds a warning when a required input widget has no value, default, or binding', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'required-input',
        name: 'Email',
        type: 'Input',
        props: { required: true },
      },
    ];

    expect(issueCodes(input)).toContain('REQUIRED_WIDGET_NOT_CONFIGURED');
  });

  it('extracts bindings from widget props and raises malformed binding errors', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'binding-widget',
        name: 'Binding Widget',
        type: 'Input',
        props: {
          value: '{{broken',
          defaultValue: 'fallback',
        },
      },
    ];

    expect(issueCodes(input)).toContain('MALFORMED_BINDING');
  });

  it('raises binding path invalid error for incomplete or double-dot moustache paths', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'binding-path-widget',
        name: 'Binding Path Widget',
        type: 'Input',
        bindings: {
          first: '{{API1.response.}}',
          second: '{{Query1..data}}',
        },
        props: { defaultValue: 'safe' },
      },
    ];

    expect(issueCodes(input)).toContain('BINDING_PATH_INVALID');
  });

  it('adds info issue for an inactive action handler', () => {
    const input = createValidInput();
    const handler: ValidationActionHandler = {
      eventType: 'onClick',
      actionType: 'executeQuery',
      queryId: 'query-1',
      active: false,
    };
    input.pages[0].components = [
      {
        id: 'inactive-handler-widget',
        name: 'Inactive Handler Widget',
        type: 'Button',
        widgetActionHandlers: [handler],
      },
    ];

    expect(issueCodes(input)).toContain('INACTIVE_ACTION_HANDLER');
  });

  it('raises missing query, api, and workflow reference errors for handler targets not found in registries', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'missing-ref-widget',
        name: 'Missing Ref Widget',
        type: 'Button',
        widgetActionHandlers: [
          {
            eventType: 'onClick',
            actionType: 'executeQuery',
            queryId: 'missing-query',
          },
          {
            eventType: 'onSubmit',
            actionType: 'executeApi',
            apiId: 'missing-api',
          },
          {
            eventType: 'onSuccess',
            actionType: 'executeWorkflow',
            workflowId: 'missing-workflow',
          },
        ],
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('QUERY_REFERENCE_NOT_FOUND');
    expect(codes).toContain('API_REFERENCE_NOT_FOUND');
    expect(codes).toContain('WORKFLOW_REFERENCE_NOT_FOUND');
  });

  it('raises broken navigation path when a navigation widget points to a page id not present in the page set', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'nav-broken',
        name: 'Broken Nav',
        type: 'Navigation',
        widgetActionHandlers: [
          {
            eventType: 'onClick',
            actionType: 'navigate',
            targetPageId: 'missing-page-id',
          },
        ],
      },
    ];

    expect(issueCodes(input)).toContain('BROKEN_NAVIGATION_PATH');
  });

  it('reveals the current page-scoped navigation validation bug for valid cross-page navigation', () => {
    const input = createValidInput();
    input.pages = [
      {
        id: 'page-a',
        name: 'Page A',
        isHome: true,
        components: [
          {
            id: 'nav-a',
            name: 'Go To Page B',
            type: 'Button',
            widgetActionHandlers: [
              {
                eventType: 'onClick',
                actionType: 'navigate',
                targetPageId: 'page-b',
              },
            ],
          },
        ],
      },
      {
        id: 'page-b',
        name: 'Page B',
        components: [
          {
            id: 'table-b',
            name: 'Results',
            type: 'Table',
          },
        ],
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('NAVIGATION_TARGET_NOT_FOUND');
    expect(codes).not.toContain('BROKEN_NAVIGATION_PATH');
  });

  it('adds query validation issues for missing statement and missing datasource', () => {
    const input = createValidInput();
    input.queries = [
      {
        id: 'query-empty',
        name: 'Empty Query',
        statement: '   ',
        datasourceId: '   ',
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('QUERY_STATEMENT_MISSING');
    expect(codes).toContain('QUERY_DATASOURCE_MISSING');
  });

  it('adds a warning when query statement does not clearly indicate CRUD operation', () => {
    const input = createValidInput();
    input.queries = [
      {
        id: 'query-opaque',
        name: 'Opaque Query',
        statement: 'MERGE INTO target USING source ON target.id = source.id',
        datasourceId: 'ds-1',
      },
    ];

    expect(issueCodes(input)).toContain('QUERY_OPERATION_UNCLEAR');
  });

  it('adds API validation issues for missing method and missing URL or baseUrl', () => {
    const input = createValidInput();
    input.apis = [
      {
        id: 'api-invalid',
        name: 'Invalid API',
        method: '   ',
        url: '   ',
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('API_METHOD_MISSING');
    expect(codes).toContain('API_URL_MISSING');
  });

  it('adds workflow validation warnings for missing input and output schemas', () => {
    const input = createValidInput();
    input.workflows = [
      {
        id: 'wf-empty',
        name: 'Workflow Empty Schemas',
        inputSchema: {},
        outputSchema: {},
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('WORKFLOW_INPUT_MISSING');
    expect(codes).toContain('WORKFLOW_OUTPUT_MISSING');
  });

  it('adds persistence warning when no persistence-related widget configuration exists', () => {
    const input = createValidInput();
    input.pages[0].components = [
      {
        id: 'non-persistent-widget',
        name: 'Non Persistent Widget',
        type: 'Input',
        props: { defaultValue: 'value' },
      },
    ];

    expect(issueCodes(input)).toContain('NO_PERSISTENCE_CONFIGURATION');
  });

  it('adds persistence error for remember-state widget with no storage key and warning for unregistered key', () => {
    const input = createValidInput();
    input.persistedKeys = ['registered.key'];
    input.pages[0].components = [
      {
        id: 'remember-only',
        name: 'Remember Only',
        type: 'Input',
        props: {
          rememberState: true,
          defaultValue: 'x',
        },
      },
      {
        id: 'wrong-key',
        name: 'Wrong Key Widget',
        type: 'Input',
        props: {
          persistKey: 'not.registered',
          defaultValue: 'y',
        },
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('PERSISTENCE_KEY_MISSING');
    expect(codes).toContain('PERSISTENCE_KEY_NOT_REGISTERED');
  });

  it('adds arsenal validation warning and info for empty arsenal, missing agent status, and draft agent', () => {
    const input = createValidInput();
    input.arsenals = [
      {
        id: 'ars-empty',
        name: 'Empty Arsenal',
        agents: [],
      },
      {
        id: 'ars-mixed',
        name: 'Mixed Arsenal',
        agents: [
          {
            id: 'agent-no-status',
            name: 'Agent No Status',
          },
          {
            id: 'agent-draft',
            name: 'Agent Draft',
            status: 'draft',
          },
        ],
      },
    ];

    const codes = issueCodes(input);
    expect(codes).toContain('ARSENAL_EMPTY');
    expect(codes).toContain('AGENT_STATUS_MISSING');
    expect(codes).toContain('AGENT_NOT_BUILT');
  });

  it('builds summary counts and module booleans consistently when issues exist', () => {
    const input = createValidInput();
    input.applicationName = '   ';
    input.pages = [];
    input.queries = [];
    input.apis = [];
    input.workflows = [];
    input.arsenals = [];

    const report = validateWithFrozenTime(input);

    expect(report.summary.failedChecks).toBe(
      report.issues.filter((issue) => issue.severity === 'error').length,
    );
    expect(report.summary.warningChecks).toBe(
      report.issues.filter((issue) => issue.severity === 'warning').length,
    );
    expect(report.summary.totalChecks).toBe(33);
    expect(report.summary.passedChecks).toBeGreaterThanOrEqual(0);
    expect(report.summary.completeUserJourneyValidated).toBe(false);
    expect(report.summary.apiAndQueryValidated).toBe(false);
    expect(report.summary.workflowValidated).toBe(false);
    expect(report.summary.persistenceValidated).toBe(false);
  });
});
```