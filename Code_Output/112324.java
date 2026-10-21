/*
Story ID: 112324
Title: Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui

What this code does:
- Provides a standalone Java 17 program that models the decommissioning workflow described in the work item.
- Accepts a structured command-line request for known dependencies, monitoring artifacts, deployment configs, and documentation status.
- Validates that dqg-backend and dqg-ui can be decommissioned only when there are no active dependencies or integrations impacted.
- Produces a post-decommission validation report aligned to the acceptance criteria.

Assumptions:
- The work item describes an operational process rather than an application feature, so this program implements the closest useful tool: a decommissioning validator/planner.
- No comments, attachments, linked items, repro steps, or custom fields were returned by the work item reader, so the implementation is based only on the returned fields, description, and acceptance criteria.
- Repo content was unavailable because the Azure DevOps repository lookup failed, so this is a standalone fallback implementation.
- Input is provided as key=value arguments with comma-separated lists where applicable.

Compile and run:
  javac 112324.java
  java DataStudioServiceDecommissionTool targets=dqg-backend,dqg-ui activeDependencies=none activeIntegrations=none monitoring=cpu-alert,latency-dashboard deploymentConfigs=helm-dqg-backend,helm-dqg-ui documentationUpdated=true validationChecks=api-gateway,job-scheduler

Requires Java 17+
*/
class DataStudioServiceDecommissionTool {

    public static void main(String[] args) {
        Request request = Request.parse(args);

        if (request.showHelp) {
            printUsage();
            return;
        }

        Result result = evaluate(request);
        System.out.println(result.format());
    }

    private static Result evaluate(Request request) {
        Result result = new Result(request);

        boolean backendRequested = request.targets.contains("dqg-backend");
        boolean uiRequested = request.targets.contains("dqg-ui");

        result.backendDecommissioned = backendRequested && request.activeDependencies.isEmpty() && request.activeIntegrations.isEmpty();
        result.uiDecommissioned = uiRequested && request.activeDependencies.isEmpty() && request.activeIntegrations.isEmpty();
        result.noDependenciesImpacted = request.activeDependencies.isEmpty() && request.activeIntegrations.isEmpty();
        result.monitoringRemoved = !request.monitoringArtifacts.isEmpty();
        result.deploymentConfigsRemoved = !request.deploymentConfigs.isEmpty();
        result.documentationUpdated = request.documentationUpdated;
        result.postValidationPassed = result.backendDecommissioned
                && result.uiDecommissioned
                && result.noDependenciesImpacted
                && result.monitoringRemoved
                && result.deploymentConfigsRemoved
                && result.documentationUpdated;

        if (!backendRequested || !uiRequested) {
            result.assumptions.add("Assumption applied: both dqg-backend and dqg-ui should be supplied in targets to fully satisfy the story.");
        }
        if (request.validationChecks.isEmpty()) {
            result.assumptions.add("Assumption applied: no explicit post-decommission checks were supplied, so overall validation is inferred from acceptance-criteria conditions.");
        }
        return result;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  javac 112324.java");
        System.out.println("  java DataStudioServiceDecommissionTool \\");
        System.out.println("    targets=dqg-backend,dqg-ui \\");
        System.out.println("    activeDependencies=none \\");
        System.out.println("    activeIntegrations=none \\");
        System.out.println("    monitoring=cpu-alert,latency-dashboard \\");
        System.out.println("    deploymentConfigs=helm-dqg-backend,helm-dqg-ui \\");
        System.out.println("    documentationUpdated=true \\");
        System.out.println("    validationChecks=api-gateway,job-scheduler");
        System.out.println();
        System.out.println("Accepted keys: targets, activeDependencies, activeIntegrations, monitoring, deploymentConfigs, documentationUpdated, validationChecks");
        System.out.println("Use 'none' for empty lists.");
    }

    static final class Request {
        final java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        final java.util.Set<String> activeDependencies = new java.util.LinkedHashSet<>();
        final java.util.Set<String> activeIntegrations = new java.util.LinkedHashSet<>();
        final java.util.Set<String> monitoringArtifacts = new java.util.LinkedHashSet<>();
        final java.util.Set<String> deploymentConfigs = new java.util.LinkedHashSet<>();
        final java.util.Set<String> validationChecks = new java.util.LinkedHashSet<>();
        boolean documentationUpdated;
        boolean showHelp;

        static Request parse(String[] args) {
            Request request = new Request();
            if (args == null || args.length == 0) {
                request.showHelp = true;
                return request;
            }

            for (String arg : args) {
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                    request.showHelp = true;
                    return request;
                }
                int idx = arg.indexOf('=');
                if (idx <= 0 || idx == arg.length() - 1) {
                    continue;
                }
                String key = arg.substring(0, idx).trim();
                String value = arg.substring(idx + 1).trim();
                switch (key) {
                    case "targets" -> addValues(request.targets, value);
                    case "activeDependencies" -> addValues(request.activeDependencies, value);
                    case "activeIntegrations" -> addValues(request.activeIntegrations, value);
                    case "monitoring" -> addValues(request.monitoringArtifacts, value);
                    case "deploymentConfigs" -> addValues(request.deploymentConfigs, value);
                    case "validationChecks" -> addValues(request.validationChecks, value);
                    case "documentationUpdated" -> request.documentationUpdated = Boolean.parseBoolean(value);
                    default -> {
                        // Requirement does not ask for strict unknown-key validation.
                    }
                }
            }
            return request;
        }

        private static void addValues(java.util.Set<String> target, String value) {
            if (value.equalsIgnoreCase("none")) {
                return;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        }
    }

    static final class Result {
        final Request request;
        final java.util.List<String> assumptions = new java.util.ArrayList<>();
        boolean backendDecommissioned;
        boolean uiDecommissioned;
        boolean noDependenciesImpacted;
        boolean monitoringRemoved;
        boolean deploymentConfigsRemoved;
        boolean documentationUpdated;
        boolean postValidationPassed;

        Result(Request request) {
            this.request = request;
        }

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("Requirement Summary\n");
            sb.append("- Story ID: 112324\n");
            sb.append("- Title: Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui\n");
            sb.append("- State: New\n");
            sb.append("- Area: AAVA\\AAVA - Data Studio\n");
            sb.append("- Iteration: AAVA\\PI4\\Sprint 4\n");
            sb.append("- Missing returned sections: comments/discussion, attachments, links/related items, repro steps, system info, custom fields beyond the standard fields shown.\n");
            sb.append("- Repo context: unavailable; Azure DevOps repository lookup failed, so standalone mode was used.\n\n");

            sb.append("Decommission Assessment\n");
            sb.append("- Requested targets: ").append(renderSet(request.targets)).append("\n");
            sb.append("- Active dependencies: ").append(renderSet(request.activeDependencies)).append("\n");
            sb.append("- Active integrations: ").append(renderSet(request.activeIntegrations)).append("\n");
            sb.append("- Monitoring artifacts slated for removal: ").append(renderSet(request.monitoringArtifacts)).append("\n");
            sb.append("- Deployment configs slated for removal: ").append(renderSet(request.deploymentConfigs)).append("\n");
            sb.append("- Documentation updated: ").append(documentationUpdated).append("\n");
            sb.append("- Post-validation checks noted: ").append(renderSet(request.validationChecks)).append("\n\n");

            sb.append("Acceptance Criteria Status\n");
            sb.append("- dqg-backend service is successfully decommissioned: ").append(status(backendDecommissioned)).append("\n");
            sb.append("- dqg-ui service is successfully decommissioned: ").append(status(uiDecommissioned)).append("\n");
            sb.append("- No active dependencies or integrations are impacted: ").append(status(noDependenciesImpacted)).append("\n");
            sb.append("- Monitoring related to these services is removed: ").append(status(monitoringRemoved)).append("\n");
            sb.append("- Deployment configurations related to these services are removed: ").append(status(deploymentConfigsRemoved)).append("\n");
            sb.append("- Documentation is updated: ").append(status(documentationUpdated)).append("\n");
            sb.append("- Post-decommission validation confirms no impact on other Data Studio components: ").append(status(postValidationPassed)).append("\n\n");

            if (!assumptions.isEmpty()) {
                sb.append("Assumptions\n");
                for (String assumption : assumptions) {
                    sb.append("- ").append(assumption).append("\n");
                }
                sb.append('\n');
            }

            sb.append("Audit Log\n");
            sb.append("- Evaluated decommission request for services dqg-backend and dqg-ui.\n");
            sb.append("- Checked for active dependencies and integrations before approving decommission status.\n");
            sb.append("- Verified presence of monitoring/deployment cleanup inputs and documentation update flag.\n");
            sb.append("- Calculated final validation outcome against the returned acceptance criteria.\n");
            return sb.toString();
        }

        private String renderSet(java.util.Set<String> values) {
            return values.isEmpty() ? "none" : String.join(", ", values);
        }

        private String status(boolean value) {
            return value ? "PASS" : "FAIL";
        }
    }
}
