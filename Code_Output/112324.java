/*
Story ID: 112324
Title: Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui

What this code does:
Creates a small standalone Java 17 utility that captures the decommissioning plan and executes
post-decommission validation checks for the services and APIs named in the story. The program
accepts lists of still-active dependencies, monitoring items, deployment configurations,
documentation items, and impacted components, then reports whether the acceptance criteria are met.

Assumptions:
- The work item describes an operational decommissioning activity rather than a directly buildable product feature.
- Because repo content was unavailable, this is implemented as a standalone Java program.
- Validation is file-input driven so the tool can be used during the decommission checklist process.
- Missing sections from the work item page: comments, attachments, linked work items, repro steps, system info, and custom fields were not returned.
- Repo context was unavailable: Azure DevOps repo tool returned "Repository not found or inaccessible", so nothing was reused from repository conventions.

Compile:
javac 112324.java

Run:
java DataStudioDecommissionValidator <input-file>

Input file format:
One key=value per line. Supported keys:
services=dqg-backend,dqg-ui
apis=<comma separated api names still active, leave blank if none>
activeDependencies=<comma separated names still depending on these services, blank if none>
monitoring=<comma separated alerts/dashboards still present, blank if none>
deploymentConfigs=<comma separated deployment configs still present, blank if none>
documentationPending=<comma separated docs not yet updated, blank if none>
impactedComponents=<comma separated components affected after decommission, blank if none>

Example:
services=dqg-backend,dqg-ui
apis=
activeDependencies=
monitoring=
deploymentConfigs=
documentationPending=
impactedComponents=
*/
class DataStudioDecommissionValidator {

    private static final String STORY_ID = "112324";
    private static final String STORY_TITLE = "Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            printUsage();
            return;
        }

        DecommissionInput input = DecommissionInput.fromFile(args[0]);
        ValidationReport report = validate(input);
        System.out.println(report.toDisplayText());
    }

    private static ValidationReport validate(DecommissionInput input) {
        ValidationReport report = new ValidationReport(input);

        report.addCheck("dqg-backend service is successfully decommissioned",
                !input.services.contains("dqg-backend".toLowerCase()) || input.decommissionedServices.contains("dqg-backend".toLowerCase()),
                !input.services.contains("dqg-backend".toLowerCase())
                        ? "Service not listed in scope file; assumed out of scope."
                        : "dqg-backend marked decommissioned=" + input.decommissionedServices.contains("dqg-backend".toLowerCase()));

        report.addCheck("dqg-ui service is successfully decommissioned",
                !input.services.contains("dqg-ui".toLowerCase()) || input.decommissionedServices.contains("dqg-ui".toLowerCase()),
                !input.services.contains("dqg-ui".toLowerCase())
                        ? "Service not listed in scope file; assumed out of scope."
                        : "dqg-ui marked decommissioned=" + input.decommissionedServices.contains("dqg-ui".toLowerCase()));

        report.addCheck("Associated APIs are reviewed and decommissioned",
                input.activeApis.isEmpty(),
                input.activeApis.isEmpty()
                        ? "No active APIs remain."
                        : "Still active APIs: " + String.join(", ", input.activeApis));

        report.addCheck("No active dependencies or integrations are impacted",
                input.activeDependencies.isEmpty(),
                input.activeDependencies.isEmpty()
                        ? "No active dependencies reported."
                        : "Active dependencies still present: " + String.join(", ", input.activeDependencies));

        report.addCheck("Monitoring, alerts, and deployment configurations related to these services are removed",
                input.monitoringItems.isEmpty() && input.deploymentConfigs.isEmpty(),
                "Monitoring remaining=" + joinOrNone(input.monitoringItems)
                        + "; Deployment configs remaining=" + joinOrNone(input.deploymentConfigs));

        report.addCheck("Documentation is updated to reflect the decommissioning",
                input.documentationPending.isEmpty(),
                input.documentationPending.isEmpty()
                        ? "No pending documentation updates."
                        : "Pending documentation updates: " + String.join(", ", input.documentationPending));

        report.addCheck("Post-decommission validation confirms no impact on other Data Studio components",
                input.impactedComponents.isEmpty(),
                input.impactedComponents.isEmpty()
                        ? "No impacted components reported."
                        : "Impacted components: " + String.join(", ", input.impactedComponents));

        return report;
    }

    private static String joinOrNone(java.util.List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static void printUsage() {
        System.out.println("Story " + STORY_ID + ": " + STORY_TITLE);
        System.out.println("Usage:");
        System.out.println("  javac 112324.java");
        System.out.println("  java DataStudioDecommissionValidator <input-file>");
    }

    static final class DecommissionInput {
        final java.util.Set<String> services;
        final java.util.Set<String> decommissionedServices;
        final java.util.List<String> activeApis;
        final java.util.List<String> activeDependencies;
        final java.util.List<String> monitoringItems;
        final java.util.List<String> deploymentConfigs;
        final java.util.List<String> documentationPending;
        final java.util.List<String> impactedComponents;

        private DecommissionInput(
                java.util.Set<String> services,
                java.util.Set<String> decommissionedServices,
                java.util.List<String> activeApis,
                java.util.List<String> activeDependencies,
                java.util.List<String> monitoringItems,
                java.util.List<String> deploymentConfigs,
                java.util.List<String> documentationPending,
                java.util.List<String> impactedComponents) {
            this.services = services;
            this.decommissionedServices = decommissionedServices;
            this.activeApis = activeApis;
            this.activeDependencies = activeDependencies;
            this.monitoringItems = monitoringItems;
            this.deploymentConfigs = deploymentConfigs;
            this.documentationPending = documentationPending;
            this.impactedComponents = impactedComponents;
        }

        static DecommissionInput fromFile(String path) throws java.io.IOException {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                if (idx < 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                values.put(key, value);
            }

            java.util.Set<String> services = toNormalizedSet(values.getOrDefault("services", "dqg-backend,dqg-ui"));
            java.util.Set<String> decommissionedServices = inferDecommissionedServices(services, values);

            return new DecommissionInput(
                    services,
                    decommissionedServices,
                    toList(values.getOrDefault("apis", "")),
                    toList(values.getOrDefault("activeDependencies", "")),
                    toList(values.getOrDefault("monitoring", "")),
                    toList(values.getOrDefault("deploymentConfigs", "")),
                    toList(values.getOrDefault("documentationPending", "")),
                    toList(values.getOrDefault("impactedComponents", ""))
            );
        }

        private static java.util.Set<String> inferDecommissionedServices(
                java.util.Set<String> services,
                java.util.Map<String, String> values) {
            java.util.Set<String> explicit = toNormalizedSet(values.getOrDefault("decommissionedServices", ""));
            if (!explicit.isEmpty()) {
                return explicit;
            }

            String status = values.getOrDefault("status", "").trim().toLowerCase();
            if ("done".equals(status) || "completed".equals(status) || "decommissioned".equals(status)) {
                return new java.util.LinkedHashSet<>(services);
            }
            return new java.util.LinkedHashSet<>();
        }

        private static java.util.Set<String> toNormalizedSet(String raw) {
            java.util.Set<String> result = new java.util.LinkedHashSet<>();
            for (String item : toList(raw)) {
                result.add(item.toLowerCase());
            }
            return result;
        }

        private static java.util.List<String> toList(String raw) {
            java.util.List<String> result = new java.util.ArrayList<>();
            if (raw == null || raw.isBlank()) {
                return result;
            }
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
            return result;
        }
    }

    static final class ValidationReport {
        private final DecommissionInput input;
        private final java.util.List<CheckResult> checks = new java.util.ArrayList<>();

        ValidationReport(DecommissionInput input) {
            this.input = input;
        }

        void addCheck(String criterion, boolean passed, String detail) {
            checks.add(new CheckResult(criterion, passed, detail));
        }

        String toDisplayText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Story ID: ").append(STORY_ID).append(System.lineSeparator());
            sb.append("Title: ").append(STORY_TITLE).append(System.lineSeparator());
            sb.append("Validation Tool: DataStudioDecommissionValidator").append(System.lineSeparator());
            sb.append(System.lineSeparator());
            sb.append("Services in scope: ")
                    .append(input.services.isEmpty() ? "none" : String.join(", ", input.services))
                    .append(System.lineSeparator());
            sb.append(System.lineSeparator());
            sb.append("Acceptance Criteria Results:").append(System.lineSeparator());

            int passCount = 0;
            for (int i = 0; i < checks.size(); i++) {
                CheckResult check = checks.get(i);
                if (check.passed) {
                    passCount++;
                }
                sb.append(i + 1)
                        .append(". [")
                        .append(check.passed ? "PASS" : "FAIL")
                        .append("] ")
                        .append(check.criterion)
                        .append(System.lineSeparator())
                        .append("   ")
                        .append(check.detail)
                        .append(System.lineSeparator());
            }

            sb.append(System.lineSeparator());
            sb.append("Overall Result: ")
                    .append(passCount == checks.size() ? "READY FOR DECOMMISSION SIGN-OFF" : "REQUIRES FOLLOW-UP")
                    .append(System.lineSeparator());
            sb.append("Passed ").append(passCount).append(" of ").append(checks.size()).append(" checks.")
                    .append(System.lineSeparator());
            return sb.toString();
        }
    }

    static final class CheckResult {
        final String criterion;
        final boolean passed;
        final String detail;

        CheckResult(String criterion, boolean passed, String detail) {
            this.criterion = criterion;
            this.passed = passed;
            this.detail = detail;
        }
    }
}
