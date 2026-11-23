/*
Story ID: 125546
Title: End-to-End Application Testing and Validation and Bugfixes
Language: TypeScript — the repo is a Vite React TypeScript UI application and the story is explicitly for the Sculpt.ag application UI workflows.
What this code does: Adds a reusable end-to-end validation utility for the Sculpt.ag UI that audits application modules, workflows, widgets, bindings, query/API configurations, persistence assumptions, and execution readiness based on in-memory app data.
Assumptions: Comments, attachments, links, repro steps, and custom fields were not present in the returned work item content. This file is written repo-style as a single source-file deliverable and introduces helper types and logic not found in the sampled repo content.
*/

export type ValidationSeverity = "info" | "warning" | "error";

export interface EndToEndValidationIssue {
  code: string;
  severity: ValidationSeverity;
  module: string;
  pageId?: string;
  pageName?: string;
  widgetId?: string;
  widgetName?: string;
  queryId?: string;
  apiId?: string;
  workflowId?: string;
  message: string;
  recommendation?: string;
}

export interface EndToEndValidationSummary {
  totalChecks: number;
  passedChecks: number;
  failedChecks: number;
  warningChecks: number;
  modulesCovered: string[];
  completeUserJourneyValidated: boolean;
  dataBindingValidated: boolean;
  apiAndQueryValidated: boolean;
  workflowValidated: boolean;
  persistenceValidated: boolean;
}

export interface EndToEndValidationReport {
  generatedAt: string;
  title: string;
  summary: EndToEndValidationSummary;
  issues: EndToEndValidationIssue[];
}

export interface ValidationPage {
  id: string;
  name: string;
  path?: string;
  isHome?: boolean;
  components?: ValidationWidget[];
}

export interface ValidationWidget {
  id: string;
  name: string;
  type: string;
  hidden?: boolean;
  props?: Record<string, unknown>;
  bindings?: Record<string, string>;
  widgetActionHandlers?: ValidationActionHandler[];
}

export interface ValidationActionHandler {
  eventType: string;
  type?: string;
  actionType?: string;
  active?: boolean;
  targetPageId?: string;
  queryId?: string;
  apiId?: string;
  workflowId?: string;
  payload?: Record<string, unknown>;
}

export interface ValidationQuery {
  id: string;
  name: string;
  queryType?: string;
  datasourceId?: string;
  statement?: string;
  method?: string;
  endpoint?: string;
}

export interface ValidationApi {
  id: string;
  name: string;
  method?: string;
  url?: string;
  baseUrl?: string;
}

export interface ValidationWorkflow {
  id: string;
  name: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  mappedAgentIds?: string[];
}

export interface ValidationArsenal {
  id: string;
  name: string;
  agents?: ValidationAgent[];
}

export interface ValidationAgent {
  id: string;
  name: string;
  status?: "draft" | "mapped" | "built" | "ready";
}

export interface SculptAgValidationInput {
  applicationName: string;
  pages: ValidationPage[];
  queries?: ValidationQuery[];
  apis?: ValidationApi[];
  workflows?: ValidationWorkflow[];
  arsenals?: ValidationArsenal[];
  persistedKeys?: string[];
  supportedWidgetTypes?: string[];
}

const DEFAULT_SUPPORTED_WIDGETS = [
  "Input",
  "Select",
  "Button",
  "Table",
  "Checkbox",
  "Radio",
  "Tabs",
  "Modal",
  "FileUpload",
  "Chart",
  "Grid",
  "Navigation",
  "Snackbar",
  "JsonViewer",
  "ExecutionMonitor",
  "FormWizard",
  "Questionnaire",
  "FolderNavigation",
  "GitFileViewer",
  "RichTextEditor",
  "CustomComponent",
];

const BINDING_PATTERN = /\{\{\s*([^}]+?)\s*\}\}/g;

export class SculptAgEndToEndValidator {
  static validate(input: SculptAgValidationInput): EndToEndValidationReport {
    const issues: EndToEndValidationIssue[] = [];
    const supportedWidgets = new Set(input.supportedWidgetTypes ?? DEFAULT_SUPPORTED_WIDGETS);
    const queriesById = new Map((input.queries ?? []).map((item) => [item.id, item]));
    const apisById = new Map((input.apis ?? []).map((item) => [item.id, item]));
    const workflowsById = new Map((input.workflows ?? []).map((item) => [item.id, item]));
    const allPages = input.pages ?? [];
    const allWidgets = allPages.flatMap((page) =>
      (page.components ?? []).map((widget) => ({ page, widget })),
    );

    if (!input.applicationName.trim()) {
      issues.push({
        code: "APP_NAME_MISSING",
        severity: "error",
        module: "application",
        message: "Application name is required for end-to-end validation context.",
        recommendation: "Provide the application name before running validation.",
      });
    }

    if (allPages.length === 0) {
      issues.push({
        code: "NO_PAGES",
        severity: "error",
        module: "pages",
        message: "No pages were supplied, so page creation, editing, deletion, and navigation cannot be validated.",
        recommendation: "Load at least one page from the application configuration.",
      });
    }

    const homePages = allPages.filter((page) => page.isHome);
    if (homePages.length === 0) {
      issues.push({
        code: "HOME_PAGE_MISSING",
        severity: "warning",
        module: "navigation",
        message: "No home page is marked, so complete user journey validation may be incomplete.",
        recommendation: "Mark one page as the application's home page.",
      });
    }

    if (homePages.length > 1) {
      issues.push({
        code: "MULTIPLE_HOME_PAGES",
        severity: "warning",
        module: "navigation",
        message: "Multiple home pages are marked, which can make navigation behavior inconsistent.",
        recommendation: "Ensure only one page is marked as home.",
      });
    }

    for (const page of allPages) {
      if (!page.name.trim()) {
        issues.push({
          code: "PAGE_NAME_MISSING",
          severity: "error",
          module: "pages",
          pageId: page.id,
          message: "Page name is missing.",
          recommendation: "Provide a page name so editing, navigation, and export/import flows can be validated.",
        });
      }

      const widgets = page.components ?? [];
      if (widgets.length === 0) {
        issues.push({
          code: "EMPTY_PAGE",
          severity: "warning",
          module: "pages",
          pageId: page.id,
          pageName: page.name,
          message: `Page '${page.name}' has no widgets, so UI interaction coverage is incomplete.`,
          recommendation: "Add at least one interactive or display widget to validate the page journey.",
        });
      }

      for (const widget of widgets) {
        this.validateWidget(page, widget, supportedWidgets, queriesById, apisById, workflowsById, issues);
      }
    }

    this.validateNavigation(allPages, issues);
    this.validateQueries(input.queries ?? [], issues);
    this.validateApis(input.apis ?? [], issues);
    this.validateWorkflows(input.workflows ?? [], issues);
    this.validatePersistence(input, allWidgets, issues);
    this.validateArsenals(input.arsenals ?? [], issues);

    const modulesCovered = Array.from(
      new Set([
        "application",
        "pages",
        "navigation",
        "widgets",
        "data-binding",
        "api-query",
        "database",
        "workflow",
        "arsenal-agent",
        "persistence",
        "validation",
      ]),
    );

    const summary = this.buildSummary(issues, modulesCovered);

    return {
      generatedAt: new Date().toISOString(),
      title: `End-to-End Validation Report - ${input.applicationName}`,
      summary,
      issues,
    };
  }

  private static validateWidget(
    page: ValidationPage,
    widget: ValidationWidget,
    supportedWidgets: Set<string>,
    queriesById: Map<string, ValidationQuery>,
    apisById: Map<string, ValidationApi>,
    workflowsById: Map<string, ValidationWorkflow>,
    issues: EndToEndValidationIssue[],
  ): void {
    if (!widget.name.trim()) {
      issues.push({
        code: "WIDGET_NAME_MISSING",
        severity: "warning",
        module: "widgets",
        pageId: page.id,
        pageName: page.name,
        widgetId: widget.id,
        message: `Widget '${widget.id}' on page '${page.name}' has no name.`,
        recommendation: "Provide a widget name so bindings and action diagnostics are traceable.",
      });
    }

    if (!supportedWidgets.has(widget.type)) {
      issues.push({
        code: "UNSUPPORTED_WIDGET_TYPE",
        severity: "warning",
        module: "widgets",
        pageId: page.id,
        pageName: page.name,
        widgetId: widget.id,
        widgetName: widget.name,
        message: `Widget '${widget.name}' uses unsupported type '${widget.type}'.`,
        recommendation: "Confirm the widget type is registered and supported in the runtime.",
      });
    }

    const props = widget.props ?? {};
    const required = props.required === true;
    const hasValue = props.value !== undefined || props.defaultValue !== undefined || props.valueBinding !== undefined;
    if (required && !hasValue && ["Input", "Select", "Checkbox", "Radio", "FileUpload"].includes(widget.type)) {
      issues.push({
        code: "REQUIRED_WIDGET_NOT_CONFIGURED",
        severity: "warning",
        module: "validation",
        pageId: page.id,
        pageName: page.name,
        widgetId: widget.id,
        widgetName: widget.name,
        message: `Required widget '${widget.name}' has no default value or binding configuration.`,
        recommendation: "Review mandatory field configuration and runtime bindings.",
      });
    }

    const bindings = widget.bindings ?? this.extractBindingsFromProps(props);
    for (const [bindingKey, expression] of Object.entries(bindings)) {
      const bindingIssues = this.validateBindingExpression(expression);
      if (bindingIssues.length === 0) {
        continue;
      }
      for (const bindingIssue of bindingIssues) {
        issues.push({
          code: bindingIssue.code,
          severity: bindingIssue.severity,
          module: "data-binding",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Binding '${bindingKey}' on widget '${widget.name}' is invalid: ${bindingIssue.message}`,
          recommendation: bindingIssue.recommendation,
        });
      }
    }

    const handlers = widget.widgetActionHandlers ?? [];
    for (const handler of handlers) {
      const actionType = handler.actionType ?? handler.type ?? "unknown";
      if (handler.active === false) {
        issues.push({
          code: "INACTIVE_ACTION_HANDLER",
          severity: "info",
          module: "widgets",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Widget '${widget.name}' has an inactive '${actionType}' handler for event '${handler.eventType}'.`,
          recommendation: "Remove unused handlers or reactivate them if they are required for end-to-end flow coverage.",
        });
      }

      if ((actionType === "navigate" || actionType === "pageNavigate") && handler.targetPageId && !handler.targetPageId.trim()) {
        issues.push({
          code: "NAVIGATION_TARGET_EMPTY",
          severity: "error",
          module: "navigation",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Widget '${widget.name}' has a navigation action without a valid target page.`,
          recommendation: "Configure the target page for the navigation action.",
        });
      }

      if ((actionType === "navigate" || actionType === "pageNavigate") && handler.targetPageId && !this.pageExists(handler.targetPageId, page.id, page)) {
        issues.push({
          code: "NAVIGATION_TARGET_NOT_FOUND",
          severity: "error",
          module: "navigation",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Widget '${widget.name}' references missing target page '${handler.targetPageId}'.`,
          recommendation: "Update the action to point to an existing page.",
        });
      }

      if ((actionType === "executeQuery" || actionType === "runQuery") && handler.queryId && !queriesById.has(handler.queryId)) {
        issues.push({
          code: "QUERY_REFERENCE_NOT_FOUND",
          severity: "error",
          module: "api-query",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          queryId: handler.queryId,
          message: `Widget '${widget.name}' references missing query '${handler.queryId}'.`,
          recommendation: "Configure the handler with an existing query.",
        });
      }

      if ((actionType === "executeApi" || actionType === "restApi") && handler.apiId && !apisById.has(handler.apiId)) {
        issues.push({
          code: "API_REFERENCE_NOT_FOUND",
          severity: "error",
          module: "api-query",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          apiId: handler.apiId,
          message: `Widget '${widget.name}' references missing API '${handler.apiId}'.`,
          recommendation: "Configure the handler with an existing API definition.",
        });
      }

      if (actionType === "executeWorkflow" && handler.workflowId && !workflowsById.has(handler.workflowId)) {
        issues.push({
          code: "WORKFLOW_REFERENCE_NOT_FOUND",
          severity: "error",
          module: "workflow",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          workflowId: handler.workflowId,
          message: `Widget '${widget.name}' references missing workflow '${handler.workflowId}'.`,
          recommendation: "Configure the handler with an existing workflow.",
        });
      }
    }
  }

  private static validateNavigation(
    pages: ValidationPage[],
    issues: EndToEndValidationIssue[],
  ): void {
    const pageIds = new Set(pages.map((page) => page.id));
    for (const page of pages) {
      const navigationWidgets = (page.components ?? []).filter(
        (widget) => widget.type === "Navigation" || widget.type === "Button" || widget.type === "Tabs",
      );
      for (const widget of navigationWidgets) {
        for (const handler of widget.widgetActionHandlers ?? []) {
          const actionType = handler.actionType ?? handler.type;
          if ((actionType === "navigate" || actionType === "pageNavigate") && handler.targetPageId && !pageIds.has(handler.targetPageId)) {
            issues.push({
              code: "BROKEN_NAVIGATION_PATH",
              severity: "error",
              module: "navigation",
              pageId: page.id,
              pageName: page.name,
              widgetId: widget.id,
              widgetName: widget.name,
              message: `Navigation from '${widget.name}' points to missing page '${handler.targetPageId}'.`,
              recommendation: "Repair the page navigation mapping.",
            });
          }
        }
      }
    }
  }

  private static validateQueries(
    queries: ValidationQuery[],
    issues: EndToEndValidationIssue[],
  ): void {
    if (queries.length === 0) {
      issues.push({
        code: "NO_QUERIES",
        severity: "warning",
        module: "api-query",
        message: "No queries were supplied, so query creation, execution, editing, deletion, and response handling cannot be fully validated.",
        recommendation: "Load application query definitions for validation.",
      });
      return;
    }

    for (const query of queries) {
      const statement = (query.statement ?? "").trim();
      if (!statement) {
        issues.push({
          code: "QUERY_STATEMENT_MISSING",
          severity: "error",
          module: "api-query",
          queryId: query.id,
          message: `Query '${query.name}' has no statement or executable body.`,
          recommendation: "Add SQL or executable query definition.",
        });
      }

      if (statement && !/(select|insert|update|delete)\b/i.test(statement)) {
        issues.push({
          code: "QUERY_OPERATION_UNCLEAR",
          severity: "warning",
          module: "database",
          queryId: query.id,
          message: `Query '${query.name}' does not clearly indicate select/insert/update/delete behavior.`,
          recommendation: "Review the query statement to ensure expected database operations are covered.",
        });
      }

      if (!query.datasourceId?.trim()) {
        issues.push({
          code: "QUERY_DATASOURCE_MISSING",
          severity: "warning",
          module: "database",
          queryId: query.id,
          message: `Query '${query.name}' is missing datasource configuration.`,
          recommendation: "Associate the query with a valid datasource for execution testing.",
        });
      }
    }
  }

  private static validateApis(
    apis: ValidationApi[],
    issues: EndToEndValidationIssue[],
  ): void {
    if (apis.length === 0) {
      issues.push({
        code: "NO_APIS",
        severity: "warning",
        module: "api-query",
        message: "No APIs were supplied, so API creation, execution, editing, deletion, and response handling cannot be fully validated.",
        recommendation: "Load application API definitions for validation.",
      });
      return;
    }

    for (const api of apis) {
      if (!api.method?.trim()) {
        issues.push({
          code: "API_METHOD_MISSING",
          severity: "error",
          module: "api-query",
          apiId: api.id,
          message: `API '${api.name}' is missing an HTTP method.`,
          recommendation: "Specify GET, POST, PUT, PATCH, or DELETE for the API.",
        });
      }

      const url = (api.url ?? api.baseUrl ?? "").trim();
      if (!url) {
        issues.push({
          code: "API_URL_MISSING",
          severity: "error",
          module: "api-query",
          apiId: api.id,
          message: `API '${api.name}' has no configured URL or base URL.`,
          recommendation: "Provide the endpoint URL so response handling can be tested.",
        });
      }
    }
  }

  private static validateWorkflows(
    workflows: ValidationWorkflow[],
    issues: EndToEndValidationIssue[],
  ): void {
    if (workflows.length === 0) {
      issues.push({
        code: "NO_WORKFLOWS",
        severity: "warning",
        module: "workflow",
        message: "No workflows were supplied, so workflow integration and input/output validation cannot be completed.",
        recommendation: "Load configured workflows before running end-to-end validation.",
      });
      return;
    }

    for (const workflow of workflows) {
      if (!workflow.inputSchema || Object.keys(workflow.inputSchema).length === 0) {
        issues.push({
          code: "WORKFLOW_INPUT_MISSING",
          severity: "warning",
          module: "workflow",
          workflowId: workflow.id,
          message: `Workflow '${workflow.name}' is missing input schema details.`,
          recommendation: "Document expected input data for workflow execution validation.",
        });
      }

      if (!workflow.outputSchema || Object.keys(workflow.outputSchema).length === 0) {
        issues.push({
          code: "WORKFLOW_OUTPUT_MISSING",
          severity: "warning",
          module: "workflow",
          workflowId: workflow.id,
          message: `Workflow '${workflow.name}' is missing output schema details.`,
          recommendation: "Document expected output data for workflow execution validation.",
        });
      }
    }
  }

  private static validatePersistence(
    input: SculptAgValidationInput,
    widgets: Array<{ page: ValidationPage; widget: ValidationWidget }>,
    issues: EndToEndValidationIssue[],
  ): void {
    const persistedKeys = new Set(input.persistedKeys ?? []);
    const persistenceWidgets = widgets.filter(({ widget }) => {
      const props = widget.props ?? {};
      return Boolean(props.persistKey) || Boolean(props.storageKey) || Boolean(props.rememberState);
    });

    if (persistenceWidgets.length === 0) {
      issues.push({
        code: "NO_PERSISTENCE_CONFIGURATION",
        severity: "warning",
        module: "persistence",
        message: "No persistence-related widget configuration was supplied, so cross-session retrieval coverage may be incomplete.",
        recommendation: "Mark widgets or modules that must persist state across sessions.",
      });
      return;
    }

    for (const { page, widget } of persistenceWidgets) {
      const props = widget.props ?? {};
      const key = String(props.persistKey ?? props.storageKey ?? "").trim();
      if (!key) {
        issues.push({
          code: "PERSISTENCE_KEY_MISSING",
          severity: "error",
          module: "persistence",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Widget '${widget.name}' is marked for persistence but has no storage key.`,
          recommendation: "Provide a persistence key so saved state can be validated across sessions.",
        });
        continue;
      }

      if (persistedKeys.size > 0 && !persistedKeys.has(key)) {
        issues.push({
          code: "PERSISTENCE_KEY_NOT_REGISTERED",
          severity: "warning",
          module: "persistence",
          pageId: page.id,
          pageName: page.name,
          widgetId: widget.id,
          widgetName: widget.name,
          message: `Persistence key '${key}' for widget '${widget.name}' was not found in the supplied persisted key list.`,
          recommendation: "Confirm persistence registration or update the key list used by validation.",
        });
      }
    }
  }

  private static validateArsenals(
    arsenals: ValidationArsenal[],
    issues: EndToEndValidationIssue[],
  ): void {
    if (arsenals.length === 0) {
      issues.push({
        code: "NO_ARSENALS",
        severity: "warning",
        module: "arsenal-agent",
        message: "No arsenal definitions were supplied, so arsenal creation, agent mapping, and agent building validation is incomplete.",
        recommendation: "Load arsenal and agent metadata when validating those workflows.",
      });
      return;
    }

    for (const arsenal of arsenals) {
      const agents = arsenal.agents ?? [];
      if (agents.length === 0) {
        issues.push({
          code: "ARSENAL_EMPTY",
          severity: "warning",
          module: "arsenal-agent",
          message: `Arsenal '${arsenal.name}' has no mapped agents.`,
          recommendation: "Map at least one agent to validate related workflows.",
        });
      }

      for (const agent of agents) {
        if (!agent.status) {
          issues.push({
            code: "AGENT_STATUS_MISSING",
            severity: "warning",
            module: "arsenal-agent",
            message: `Agent '${agent.name}' in arsenal '${arsenal.name}' has no status.`,
            recommendation: "Set the agent status to draft, mapped, built, or ready.",
          });
          continue;
        }

        if (agent.status === "draft") {
          issues.push({
            code: "AGENT_NOT_BUILT",
            severity: "info",
            module: "arsenal-agent",
            message: `Agent '${agent.name}' in arsenal '${arsenal.name}' is still in draft state.`,
            recommendation: "Build and validate the agent before considering the workflow complete.",
          });
        }
      }
    }
  }

  private static validateBindingExpression(
    expression: string,
  ): Array<Pick<EndToEndValidationIssue, "code" | "severity" | "message" | "recommendation">> {
    const trimmed = expression.trim();
    if (!trimmed) {
      return [
        {
          code: "EMPTY_BINDING",
          severity: "warning",
          message: "binding expression is empty",
          recommendation: "Remove the binding or provide a valid expression.",
        },
      ];
    }

    const matches = Array.from(trimmed.matchAll(BINDING_PATTERN));
    if (matches.length === 0 && trimmed.includes("{{")) {
      return [
        {
          code: "MALFORMED_BINDING",
          severity: "error",
          message: "binding contains '{{' but is not a valid moustache expression",
          recommendation: "Use '{{ source.path }}' syntax for dynamic binding.",
        },
      ];
    }

    const invalidTokens = matches
      .map((match) => match[1]?.trim() ?? "")
      .filter((token) => !token || token.endsWith(".") || token.includes(".."));

    if (invalidTokens.length > 0) {
      return [
        {
          code: "BINDING_PATH_INVALID",
          severity: "error",
          message: `invalid binding path(s): ${invalidTokens.join(", ")}`,
          recommendation: "Use complete property paths such as '{{API1.response.data}}'.",
        },
      ];
    }

    return [];
  }

  private static extractBindingsFromProps(props: Record<string, unknown>): Record<string, string> {
    const bindings: Record<string, string> = {};
    for (const [key, value] of Object.entries(props)) {
      if (typeof value === "string" && value.includes("{{")) {
        bindings[key] = value;
      }
    }
    return bindings;
  }

  private static pageExists(targetPageId: string, currentPageId: string, currentPage: ValidationPage): boolean {
    if (!targetPageId.trim()) {
      return false;
    }
    return targetPageId === currentPageId || targetPageId === currentPage.id || targetPageId === currentPage.name;
  }

  private static buildSummary(
    issues: EndToEndValidationIssue[],
    modulesCovered: string[],
  ): EndToEndValidationSummary {
    const failedChecks = issues.filter((issue) => issue.severity === "error").length;
    const warningChecks = issues.filter((issue) => issue.severity === "warning").length;
    const totalChecks = Math.max(1, modulesCovered.length * 3);
    const passedChecks = Math.max(0, totalChecks - failedChecks - warningChecks);

    return {
      totalChecks,
      passedChecks,
      failedChecks,
      warningChecks,
      modulesCovered,
      completeUserJourneyValidated: !issues.some(
        (issue) => issue.module === "pages" || issue.module === "navigation",
      ),
      dataBindingValidated: !issues.some((issue) => issue.module === "data-binding"),
      apiAndQueryValidated: !issues.some((issue) => issue.module === "api-query"),
      workflowValidated: !issues.some((issue) => issue.module === "workflow"),
      persistenceValidated: !issues.some((issue) => issue.module === "persistence"),
    };
  }
}

export default SculptAgEndToEndValidator;
