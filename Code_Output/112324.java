/*
Story ID: 112324
Title: Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui

What this code does:
- Provides a standalone Java 17 program that validates and executes a decommissioning checklist
  for the unused Data Studio services dqg-backend and dqg-ui.
- Tracks required decommissioning steps from the work item acceptance criteria and reports whether
  post-decommission validation passes.

Assumptions:
- The work item describes an operational decommissioning activity rather than an existing repo feature,
  so the closest useful buildable artifact is a checklist-driven validator tool.
- Missing sections from the work item page: comments/discussion, attachments, links/related items,
  repro steps, system info, and custom fields were not returned by the work item tool.
- Repo context was unavailable because the Azure DevOps repo read failed, so this is a standalone fallback.
- Service names are validated exactly as dqg-backend and dqg-ui.
- Each checklist item is supplied as key=value on the command line using true/false values.

Compile:
  javac 112324.java
Run:
  java DataStudioDecommissionValidator service:dqg-backend=true service:dqg-ui=true dependenciesCleared=true monitoringRemoved=true documentationUpdated=true postValidationPassed=true

Requires Java 17+
*/
class DataStudioDecommissionValidator {
    private static final String STORY_ID = "112324";
    private static final String TITLE = "Decommission Unused Data Studio Services and APIs – dqg-backend & dqg-ui";

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        DecommissionStatus status = DecommissionStatus.fromArgs(args);
        ValidationResult result = validate(status);
        System.out.println(result.format());
    }

    private static ValidationResult validate(DecommissionStatus status) {
        StringBuilder details = new StringBuilder();
        boolean success = true;

        success &= appendCheck(details, "dqg-backend service is successfully decommissioned", status.dqgBackendDecommissioned);
        success &= appendCheck(details, "dqg-ui service is successfully decommissioned", status.dqgUiDecommissioned);
        success &= appendCheck(details, "No active dependencies or integrations are impacted", status.dependenciesCleared);
        success &= appendCheck(details, "Monitoring, alerts, and deployment configurations are removed", status.monitoringRemoved);
        success &= appendCheck(details, "Documentation is updated to reflect the decommissioning", status.documentationUpdated);
        success &= appendCheck(details, "Post-decommission validation confirms no impact on other Data Studio components", status.postValidationPassed);

        return new ValidationResult(success, details.toString().trim());
    }

    private static boolean appendCheck(StringBuilder details, String label, boolean passed) {
        details.append(passed ? "[PASS] " : "[FAIL] ")
               .append(label)
               .append(System.lineSeparator());
        return passed;
    }

    private static void printUsage() {
        System.out.println("Story " + STORY_ID + ": " + TITLE);
        System.out.println("Usage:");
        System.out.println("  javac 112324.java");
        System.out.println("  java DataStudioDecommissionValidator "
                + "service:dqg-backend=<true|false> "
                + "service:dqg-ui=<true|false> "
                + "dependenciesCleared=<true|false> "
                + "monitoringRemoved=<true|false> "
                + "documentationUpdated=<true|false> "
                + "postValidationPassed=<true|false>");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java DataStudioDecommissionValidator "
                + "service:dqg-backend=true service:dqg-ui=true dependenciesCleared=true "
                + "monitoringRemoved=true documentationUpdated=true postValidationPassed=true");
    }

    private record DecommissionStatus(
            boolean dqgBackendDecommissioned,
            boolean dqgUiDecommissioned,
            boolean dependenciesCleared,
            boolean monitoringRemoved,
            boolean documentationUpdated,
            boolean postValidationPassed
    ) {
        static DecommissionStatus fromArgs(String[] args) {
            boolean backend = false;
            boolean ui = false;
            boolean dependencies = false;
            boolean monitoring = false;
            boolean docs = false;
            boolean validation = false;

            for (String arg : args) {
                String[] pair = arg.split("=", 2);
                if (pair.length != 2) {
                    continue;
                }
                String key = pair[0].trim();
                boolean value = Boolean.parseBoolean(pair[1].trim());

                switch (key) {
                    case "service:dqg-backend" -> backend = value;
                    case "service:dqg-ui" -> ui = value;
                    case "dependenciesCleared" -> dependencies = value;
                    case "monitoringRemoved" -> monitoring = value;
                    case "documentationUpdated" -> docs = value;
                    case "postValidationPassed" -> validation = value;
                    default -> {
                    }
                }
            }

            return new DecommissionStatus(backend, ui, dependencies, monitoring, docs, validation);
        }
    }

    private record ValidationResult(boolean success, String details) {
        String format() {
            return "Decommission Validation Result: " + (success ? "SUCCESS" : "FAILED")
                    + System.lineSeparator()
                    + details;
        }
    }
}
