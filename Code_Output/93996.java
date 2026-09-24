/*
User Story ID: 93996
Title: Claude API - End-to-End Lakehouse in a Day – Requirements to Reporting

What this program does:
- Demonstrates an end-to-end "Lakehouse workflow" from business requirements to processing and final reporting.
- Incorporates and demonstrates 11 framework components via a simple, runnable console workflow:
  1) Decomposition (workflow broken into stages/steps)
  2) Step enforcement (cannot run steps out of order)
  3) Context passing (shared context object passed through steps)
  4) Looping (each step can be rerun without restarting entire workflow)
  5) Gating (human-in-the-loop approval at gates)
  6) Reviews (human feedback at review points)
  7) Hooks (before/after step hooks)
  8) Validation (basic required-input validation)
  9) Planning (generates a simple execution plan from requirements)
 10) Audit logging (captures actions/decisions/reruns)
 11) Reporting (generates final Markdown or HTML report)

How to compile:
  javac 93996.java

How to run:
  java 93996

Assumption (due to requirement ambiguity):
- This is a demonstrator / simulation of a Lakehouse workflow (no external Lakehouse platform).
  It creates a small in-memory dataset and writes simple CSV outputs to a local folder.
*/

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class 93996 {

    // ======== Framework: Context Passing ========
    static final class WorkflowContext {
        final String userStoryId;
        String businessProblem;
        String successCriteria;
        String dataSources;
        String grain;
        String kpis;

        // Derived artifacts
        List<String> planSteps = new ArrayList<>();
        List<Map<String, String>> rawData = new ArrayList<>();
        List<Map<String, String>> curatedData = new ArrayList<>();
        Map<String, String> metrics = new LinkedHashMap<>();

        // Execution bookkeeping for step enforcement
        final EnumSet<StepId> completed = EnumSet.noneOf(StepId.class);

        // Output config
        Path outputDir;
        String reportFormat; // md or html
        String reportContent;

        WorkflowContext(String userStoryId) {
            this.userStoryId = userStoryId;
        }
    }

    // ======== Framework: Audit Log ========
    static final class AuditEvent {
        final Instant ts = Instant.now();
        final String type;
        final String step;
        final String message;

        AuditEvent(String type, String step, String message) {
            this.type = type;
            this.step = step;
            this.message = message;
        }
    }

    static final class AuditLog {
        private final List<AuditEvent> events = new ArrayList<>();

        void add(String type, String step, String message) {
            events.add(new AuditEvent(type, step, message));
        }

        List<AuditEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    // ======== Framework: Decomposition + Step Enforcement ========
    enum StepId {
        REQUIREMENTS,
        PLANNING,
        INGESTION,
        TRANSFORMATION,
        METRICS,
        REPORT
    }

    interface Step {
        StepId id();

        String name();

        EnumSet<StepId> dependsOn();

        void execute(WorkflowContext ctx, Scanner in, AuditLog audit) throws Exception;
    }

    // ======== Framework: Hooks ========
    interface Hook {
        void before(Step step, WorkflowContext ctx, AuditLog audit);

        void after(Step step, WorkflowContext ctx, AuditLog audit);
    }

    static final class StandardHook implements Hook {
        @Override
        public void before(Step step, WorkflowContext ctx, AuditLog audit) {
            audit.add("HOOK_BEFORE", step.name(), "Starting step");
        }

        @Override
        public void after(Step step, WorkflowContext ctx, AuditLog audit) {
            audit.add("HOOK_AFTER", step.name(), "Completed step");
        }
    }

    // ======== Framework: Gating / Human-in-the-loop ========
    static boolean gate(Scanner in, AuditLog audit, String gateName, String prompt) {
        while (true) {
            System.out.println();
            System.out.println("=== GATE: " + gateName + " ===");
            System.out.println(prompt);
            System.out.println("Choose: [A]pprove / [S]top");
            System.out.print("> ");
            String s = in.nextLine().trim().toUpperCase(Locale.ROOT);
            if (s.equals("A") || s.equals("APPROVE")) {
                audit.add("GATE", gateName, "Approved");
                return true;
            }
            if (s.equals("S") || s.equals("STOP")) {
                audit.add("GATE", gateName, "Stopped by user");
                return false;
            }
            System.out.println("Invalid choice. Enter A or S.");
        }
    }

    static final class GateDecision {
        enum Action { APPROVE_CONTINUE, UPDATE_INPUTS_AND_RERUN, RERUN_NO_CHANGES, STOP }

        final Action action;
        final Map<String, String> updates;

        GateDecision(Action action, Map<String, String> updates) {
            this.action = action;
            this.updates = updates;
        }
    }

    // Gate that allows approve/stop OR rerun step with optional updates
    static GateDecision reviewGate(Scanner in, AuditLog audit, String gateName, String reviewSummary,
                                  List<String> updatableFields) {
        while (true) {
            System.out.println();
            System.out.println("=== REVIEW: " + gateName + " ===");
            System.out.println(reviewSummary);
            System.out.println("Choose: [C]ontinue / [U]pdate inputs + rerun current step / [R]erun current step / [S]top");
            System.out.print("> ");
            String s = in.nextLine().trim().toUpperCase(Locale.ROOT);
            switch (s) {
                case "C":
                case "CONTINUE":
                    audit.add("REVIEW", gateName, "Continue approved");
                    return new GateDecision(GateDecision.Action.APPROVE_CONTINUE, Collections.emptyMap());
                case "U":
                case "UPDATE": {
                    Map<String, String> updates = new LinkedHashMap<>();
                    audit.add("REVIEW", gateName, "User chose update inputs");
                    for (String f : updatableFields) {
                        System.out.print("Update '" + f + "' (leave blank to keep): ");
                        String v = in.nextLine();
                        if (v != null && !v.trim().isEmpty()) {
                            updates.put(f, v.trim());
                        }
                    }
                    audit.add("REVIEW", gateName, "Updates provided: " + updates.keySet());
                    return new GateDecision(GateDecision.Action.UPDATE_INPUTS_AND_RERUN, updates);
                }
                case "R":
                case "RERUN":
                    audit.add("REVIEW", gateName, "User chose rerun without changes");
                    return new GateDecision(GateDecision.Action.RERUN_NO_CHANGES, Collections.emptyMap());
                case "S":
                case "STOP":
                    audit.add("REVIEW", gateName, "Stopped by user");
                    return new GateDecision(GateDecision.Action.STOP, Collections.emptyMap());
                default:
                    System.out.println("Invalid choice. Enter C/U/R/S.");
            }
        }
    }

    // ======== Steps ========

    static final class RequirementsStep implements Step {
        public StepId id() { return StepId.REQUIREMENTS; }

        public String name() { return "Requirements Gathering"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.noneOf(StepId.class); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) {
            System.out.println("\n--- Requirements Gathering ---");
            ctx.businessProblem = promptNonEmpty(in, audit, name(), "Business problem statement");
            ctx.successCriteria = promptNonEmpty(in, audit, name(), "Success criteria (what does good look like?)");
            ctx.dataSources = promptNonEmpty(in, audit, name(), "Data sources (e.g., CRM, transactions)");
            ctx.grain = promptNonEmpty(in, audit, name(), "Data grain (e.g., per order, per customer per day)");
            ctx.kpis = promptNonEmpty(in, audit, name(), "KPIs to report (comma-separated)");

            audit.add("OUTPUT", name(), "Captured requirements");
        }

        private static String promptNonEmpty(Scanner in, AuditLog audit, String step, String label) {
            while (true) {
                System.out.print(label + ": ");
                String s = in.nextLine();
                if (s != null && !s.trim().isEmpty()) {
                    String v = s.trim();
                    audit.add("INPUT", step, label + " = " + v);
                    return v;
                }
                System.out.println("Please enter a value.");
            }
        }
    }

    static final class PlanningStep implements Step {
        public StepId id() { return StepId.PLANNING; }

        public String name() { return "Planning / Decomposition"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.of(StepId.REQUIREMENTS); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) {
            System.out.println("\n--- Planning / Decomposition ---");
            ctx.planSteps.clear();
            ctx.planSteps.add("1. Ingest raw data for sources: " + ctx.dataSources);
            ctx.planSteps.add("2. Curate/transform data to grain: " + ctx.grain);
            ctx.planSteps.add("3. Compute KPIs: " + ctx.kpis);
            ctx.planSteps.add("4. Produce final report (Markdown/HTML)");

            audit.add("OUTPUT", name(), "Generated execution plan with " + ctx.planSteps.size() + " items");

            System.out.println("Proposed plan:");
            for (String p : ctx.planSteps) System.out.println("  - " + p);

            if (!gate(in, audit, "Plan Approval", "Approve the plan to proceed?")) {
                throw new StopWorkflowException("Stopped at plan gate");
            }
        }
    }

    static final class IngestionStep implements Step {
        public StepId id() { return StepId.INGESTION; }

        public String name() { return "Ingestion (Bronze)"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.of(StepId.PLANNING); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) throws IOException {
            System.out.println("\n--- Ingestion (Bronze) ---");

            // Simulated raw data based on requirements
            ctx.rawData = generateSampleData(ctx, audit);
            audit.add("OUTPUT", name(), "Generated in-memory raw dataset rows=" + ctx.rawData.size());

            writeCsv(ctx.outputDir.resolve("bronze_raw.csv"), ctx.rawData);
            audit.add("OUTPUT", name(), "Wrote " + ctx.outputDir.resolve("bronze_raw.csv"));

            GateDecision gd = reviewGate(in, audit, "Ingestion Review",
                    "Raw rows: " + ctx.rawData.size() + " (written to bronze_raw.csv)",
                    Arrays.asList("dataSources", "grain"));
            applyGateDecisionOrThrow(ctx, audit, gd, name());
        }

        private static List<Map<String, String>> generateSampleData(WorkflowContext ctx, AuditLog audit) {
            List<Map<String, String>> rows = new ArrayList<>();
            // Create a tiny dataset with dates, customer, amount; grain is used later.
            String[] customers = {"CUST-001", "CUST-002", "CUST-003"};
            LocalDate base = LocalDate.now().minusDays(7);
            Random r = new Random(42);
            for (int i = 0; i < 18; i++) {
                Map<String, String> row = new LinkedHashMap<>();
                LocalDate d = base.plusDays(i % 6);
                String cust = customers[i % customers.length];
                int amount = 50 + r.nextInt(200);
                row.put("date", d.toString());
                row.put("customer_id", cust);
                row.put("amount", String.valueOf(amount));
                row.put("source", ctx.dataSources);
                rows.add(row);
            }
            audit.add("ACTION", "SampleData", "Generated sample data for demonstration");
            return rows;
        }
    }

    static final class TransformationStep implements Step {
        public StepId id() { return StepId.TRANSFORMATION; }

        public String name() { return "Transformation (Silver)"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.of(StepId.INGESTION); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) throws IOException {
            System.out.println("\n--- Transformation (Silver) ---");

            // Curate: ensure fields exist, normalize types. We'll aggregate by date for a simple "curated" table.
            Map<String, Integer> dailyTotal = new TreeMap<>();
            for (Map<String, String> row : ctx.rawData) {
                String date = row.get("date");
                int amount = parseIntSafe(row.get("amount"));
                dailyTotal.merge(date, amount, Integer::sum);
            }

            List<Map<String, String>> curated = new ArrayList<>();
            for (Map.Entry<String, Integer> e : dailyTotal.entrySet()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("date", e.getKey());
                row.put("daily_sales", String.valueOf(e.getValue()));
                curated.add(row);
            }
            ctx.curatedData = curated;

            writeCsv(ctx.outputDir.resolve("silver_curated.csv"), ctx.curatedData);
            audit.add("OUTPUT", name(), "Wrote " + ctx.outputDir.resolve("silver_curated.csv") + " rows=" + curated.size());

            GateDecision gd = reviewGate(in, audit, "Transformation Review",
                    "Curated rows: " + curated.size() + " (written to silver_curated.csv)",
                    Arrays.asList("grain"));
            applyGateDecisionOrThrow(ctx, audit, gd, name());
        }

        private static int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    static final class MetricsStep implements Step {
        public StepId id() { return StepId.METRICS; }

        public String name() { return "Metrics (Gold)"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.of(StepId.TRANSFORMATION); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) throws IOException {
            System.out.println("\n--- Metrics (Gold) ---");

            // Compute a few basic metrics; interpret KPIs loosely as a list but always compute these.
            int days = ctx.curatedData.size();
            long total = 0;
            int max = Integer.MIN_VALUE;
            int min = Integer.MAX_VALUE;
            String maxDay = "";
            String minDay = "";

            for (Map<String, String> row : ctx.curatedData) {
                int v = Integer.parseInt(row.get("daily_sales"));
                total += v;
                if (v > max) {
                    max = v;
                    maxDay = row.get("date");
                }
                if (v < min) {
                    min = v;
                    minDay = row.get("date");
                }
            }

            double avg = days == 0 ? 0.0 : ((double) total) / days;
            ctx.metrics.clear();
            ctx.metrics.put("days", String.valueOf(days));
            ctx.metrics.put("total_sales", String.valueOf(total));
            ctx.metrics.put("avg_daily_sales", String.format(Locale.ROOT, "%.2f", avg));
            ctx.metrics.put("max_daily_sales", max == Integer.MIN_VALUE ? "0" : String.valueOf(max));
            ctx.metrics.put("max_day", maxDay);
            ctx.metrics.put("min_daily_sales", min == Integer.MAX_VALUE ? "0" : String.valueOf(min));
            ctx.metrics.put("min_day", minDay);

            List<Map<String, String>> gold = new ArrayList<>();
            for (Map.Entry<String, String> e : ctx.metrics.entrySet()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("metric", e.getKey());
                row.put("value", e.getValue());
                gold.add(row);
            }
            writeCsv(ctx.outputDir.resolve("gold_metrics.csv"), gold);
            audit.add("OUTPUT", name(), "Wrote " + ctx.outputDir.resolve("gold_metrics.csv") + " metrics=" + ctx.metrics.size());

            GateDecision gd = reviewGate(in, audit, "Metrics Review",
                    "Computed metrics (written to gold_metrics.csv)",
                    Arrays.asList("kpis"));
            applyGateDecisionOrThrow(ctx, audit, gd, name());
        }
    }

    static final class ReportStep implements Step {
        public StepId id() { return StepId.REPORT; }

        public String name() { return "Final Reporting"; }

        public EnumSet<StepId> dependsOn() { return EnumSet.of(StepId.METRICS); }

        public void execute(WorkflowContext ctx, Scanner in, AuditLog audit) throws IOException {
            System.out.println("\n--- Final Reporting ---");

            ctx.reportFormat = promptFormat(in, audit);
            ctx.reportContent = generateReport(ctx, audit);

            String ext = ctx.reportFormat.equals("html") ? ".html" : ".md";
            Path reportPath = ctx.outputDir.resolve("final_report" + ext);
            Files.writeString(reportPath, ctx.reportContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            audit.add("OUTPUT", name(), "Wrote report: " + reportPath);

            System.out.println("Report written to: " + reportPath.toAbsolutePath());
            System.out.println("Workflow complete.");
        }

        private static String promptFormat(Scanner in, AuditLog audit) {
            while (true) {
                System.out.print("Report format [md/html] (default md): ");
                String s = in.nextLine().trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) s = "md";
                if (s.equals("md") || s.equals("markdown")) {
                    audit.add("INPUT", "Reporting", "reportFormat=md");
                    return "md";
                }
                if (s.equals("html")) {
                    audit.add("INPUT", "Reporting", "reportFormat=html");
                    return "html";
                }
                System.out.println("Please enter 'md' or 'html'.");
            }
        }

        private static String generateReport(WorkflowContext ctx, AuditLog audit) {
            audit.add("ACTION", "Reporting", "Generating final report");
            if (ctx.reportFormat.equals("html")) {
                return HtmlReport.render(ctx, audit);
            }
            return MarkdownReport.render(ctx, audit);
        }
    }

    // ======== Reporting renderers ========

    static final class MarkdownReport {
        static String render(WorkflowContext ctx, AuditLog audit) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Lakehouse Workflow Report\n\n");
            sb.append("- User Story ID: ").append(ctx.userStoryId).append("\n");
            sb.append("- Generated: ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("\n");
            sb.append("- Output Directory: ").append(ctx.outputDir.toAbsolutePath()).append("\n\n");

            sb.append("## Requirements\n");
            sb.append("- Business problem: ").append(escapeMd(ctx.businessProblem)).append("\n");
            sb.append("- Success criteria: ").append(escapeMd(ctx.successCriteria)).append("\n");
            sb.append("- Data sources: ").append(escapeMd(ctx.dataSources)).append("\n");
            sb.append("- Data grain: ").append(escapeMd(ctx.grain)).append("\n");
            sb.append("- KPIs: ").append(escapeMd(ctx.kpis)).append("\n\n");

            sb.append("## Execution Plan (Decomposition)\n");
            for (String p : ctx.planSteps) sb.append("- ").append(escapeMd(p)).append("\n");
            sb.append("\n");

            sb.append("## Outputs\n");
            sb.append("- Bronze: bronze_raw.csv (rows: ").append(ctx.rawData.size()).append(")\n");
            sb.append("- Silver: silver_curated.csv (rows: ").append(ctx.curatedData.size()).append(")\n");
            sb.append("- Gold: gold_metrics.csv (metrics: ").append(ctx.metrics.size()).append(")\n\n");

            sb.append("## Metrics Summary\n");
            for (Map.Entry<String, String> e : ctx.metrics.entrySet()) {
                sb.append("- ").append(escapeMd(e.getKey())).append(": ").append(escapeMd(e.getValue())).append("\n");
            }
            sb.append("\n");

            sb.append("## Agents Executed / Steps Performed (Step Enforcement)\n");
            for (StepId s : StepId.values()) {
                sb.append("- ").append(s.name()).append(": ").append(ctx.completed.contains(s) ? "DONE" : "SKIPPED").append("\n");
            }
            sb.append("\n");

            sb.append("## Audit Log (Decisions, Gates, Inputs, Outputs, Reruns)\n");
            sb.append("| Timestamp | Type | Step | Message |\n");
            sb.append("|---|---|---|---|\n");
            for (AuditEvent e : audit.events()) {
                sb.append("|")
                  .append(e.ts)
                  .append("|")
                  .append(escapeMd(e.type))
                  .append("|")
                  .append(escapeMd(e.step))
                  .append("|")
                  .append(escapeMd(e.message))
                  .append("|\n");
            }
            sb.append("\n");

            sb.append("## Framework Components Demonstrated\n");
            sb.append("1. Decomposition (stages: requirements→planning→ingestion→transformation→metrics→report)\n");
            sb.append("2. Step enforcement (dependencies checked before execution)\n");
            sb.append("3. Context passing (WorkflowContext used across all steps)\n");
            sb.append("4. Looping (rerun current step from review gates)\n");
            sb.append("5. Gating (plan approval + review points)\n");
            sb.append("6. Reviews (review gates allow updates/stop/continue)\n");
            sb.append("7. Hooks (before/after step hook events logged)\n");
            sb.append("8. Validation (non-empty requirement fields)\n");
            sb.append("9. Planning (plan generated from requirements)\n");
            sb.append("10. Audit logging (full event timeline included)\n");
            sb.append("11. Reporting (Markdown/HTML report generation)\n");

            return sb.toString();
        }

        private static String escapeMd(String s) {
            if (s == null) return "";
            return s.replace("|", "\\|").replace("\n", " ");
        }
    }

    static final class HtmlReport {
        static String render(WorkflowContext ctx, AuditLog audit) {
            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html><html><head><meta charset='utf-8'>");
            sb.append("<title>Lakehouse Workflow Report</title>");
            sb.append("<style>body{font-family:Arial, sans-serif; margin:20px;} table{border-collapse:collapse; width:100%;} th,td{border:1px solid #ccc; padding:6px;} th{background:#f4f4f4; text-align:left;} code{background:#f7f7f7; padding:2px 4px;}</style>");
            sb.append("</head><body>");

            sb.append("<h1>Lakehouse Workflow Report</h1>");
            sb.append("<ul>");
            sb.append("<li><b>User Story ID:</b> ").append(esc(ctx.userStoryId)).append("</li>");
            sb.append("<li><b>Generated:</b> ").append(esc(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))).append("</li>");
            sb.append("<li><b>Output Directory:</b> ").append(esc(ctx.outputDir.toAbsolutePath().toString())).append("</li>");
            sb.append("</ul>");

            sb.append("<h2>Requirements</h2>");
            sb.append("<ul>");
            sb.append("<li><b>Business problem:</b> ").append(esc(ctx.businessProblem)).append("</li>");
            sb.append("<li><b>Success criteria:</b> ").append(esc(ctx.successCriteria)).append("</li>");
            sb.append("<li><b>Data sources:</b> ").append(esc(ctx.dataSources)).append("</li>");
            sb.append("<li><b>Data grain:</b> ").append(esc(ctx.grain)).append("</li>");
            sb.append("<li><b>KPIs:</b> ").append(esc(ctx.kpis)).append("</li>");
            sb.append("</ul>");

            sb.append("<h2>Execution Plan (Decomposition)</h2><ul>");
            for (String p : ctx.planSteps) sb.append("<li>").append(esc(p)).append("</li>");
            sb.append("</ul>");

            sb.append("<h2>Outputs</h2><ul>");
            sb.append("<li><b>Bronze:</b> <code>bronze_raw.csv</code> (rows: ").append(ctx.rawData.size()).append(")</li>");
            sb.append("<li><b>Silver:</b> <code>silver_curated.csv</code> (rows: ").append(ctx.curatedData.size()).append(")</li>");
            sb.append("<li><b>Gold:</b> <code>gold_metrics.csv</code> (metrics: ").append(ctx.metrics.size()).append(")</li>");
            sb.append("</ul>");

            sb.append("<h2>Metrics Summary</h2><ul>");
            for (Map.Entry<String, String> e : ctx.metrics.entrySet()) {
                sb.append("<li><b>").append(esc(e.getKey())).append(":</b> ").append(esc(e.getValue())).append("</li>");
            }
            sb.append("</ul>");

            sb.append("<h2>Agents Executed / Steps Performed (Step Enforcement)</h2><ul>");
            for (StepId s : StepId.values()) {
                sb.append("<li><b>").append(esc(s.name())).append("</b>: ").append(ctx.completed.contains(s) ? "DONE" : "SKIPPED").append("</li>");
            }
            sb.append("</ul>");

            sb.append("<h2>Audit Log (Decisions, Gates, Inputs, Outputs, Reruns)</h2>");
            sb.append("<table><thead><tr><th>Timestamp</th><th>Type</th><th>Step</th><th>Message</th></tr></thead><tbody>");
            for (AuditEvent e : audit.events()) {
                sb.append("<tr><td>").append(esc(e.ts.toString())).append("</td><td>").append(esc(e.type))
                        .append("</td><td>").append(esc(e.step)).append("</td><td>").append(esc(e.message)).append("</td></tr>");
            }
            sb.append("</tbody></table>");

            sb.append("<h2>Framework Components Demonstrated</h2>");
            sb.append("<ol>");
            sb.append("<li>Decomposition</li>");
            sb.append("<li>Step enforcement</li>");
            sb.append("<li>Context passing</li>");
            sb.append("<li>Looping (rerun current step)</li>");
            sb.append("<li>Gating</li>");
            sb.append("<li>Reviews (update/continue/stop)</li>");
            sb.append("<li>Hooks</li>");
            sb.append("<li>Validation</li>");
            sb.append("<li>Planning</li>");
            sb.append("<li>Audit logging</li>");
            sb.append("<li>Reporting</li>");
            sb.append("</ol>");

            sb.append("</body></html>");
            return sb.toString();
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }
    }

    // ======== Engine ========

    static final class StopWorkflowException extends RuntimeException {
        StopWorkflowException(String message) { super(message); }
    }

    static final class Engine {
        private final List<Step> steps;
        private final List<Hook> hooks;
        private final AuditLog audit;

        Engine(List<Step> steps, List<Hook> hooks, AuditLog audit) {
            this.steps = steps;
            this.hooks = hooks;
            this.audit = audit;
        }

        void run(WorkflowContext ctx, Scanner in) {
            int idx = 0;
            while (idx < steps.size()) {
                Step step = steps.get(idx);

                enforceDependencies(ctx, step);

                // ======== Framework: Looping (rerun a step) ========
                boolean done = false;
                while (!done) {
                    for (Hook h : hooks) h.before(step, ctx, audit);
                    audit.add("STEP_START", step.name(), "Executing");
                    try {
                        step.execute(ctx, in, audit);
                        ctx.completed.add(step.id());
                        audit.add("STEP_END", step.name(), "Success");
                        for (Hook h : hooks) h.after(step, ctx, audit);
                        done = true;
                    } catch (StopWorkflowException sw) {
                        audit.add("STOP", step.name(), sw.getMessage());
                        throw sw;
                    } catch (RerunStepException rr) {
                        audit.add("RERUN", step.name(), rr.getMessage());
                        // loop again
                    } catch (Exception ex) {
                        audit.add("ERROR", step.name(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        throw new RuntimeException(ex);
                    }
                }

                idx++;
            }
        }

        private void enforceDependencies(WorkflowContext ctx, Step step) {
            for (StepId dep : step.dependsOn()) {
                if (!ctx.completed.contains(dep)) {
                    audit.add("ENFORCEMENT", step.name(), "Missing dependency: " + dep);
                    throw new IllegalStateException("Step enforcement: cannot run " + step.id() + " before " + dep);
                }
            }
            audit.add("ENFORCEMENT", step.name(), "Dependencies satisfied: " + step.dependsOn());
        }
    }

    static final class RerunStepException extends RuntimeException {
        RerunStepException(String message) { super(message); }
    }

    static void applyGateDecisionOrThrow(WorkflowContext ctx, AuditLog audit, GateDecision gd, String stepName) {
        if (gd.action == GateDecision.Action.STOP) {
            throw new StopWorkflowException("Stopped at review gate");
        }
        if (gd.action == GateDecision.Action.RERUN_NO_CHANGES) {
            throw new RerunStepException("Rerun requested (no changes)");
        }
        if (gd.action == GateDecision.Action.UPDATE_INPUTS_AND_RERUN) {
            for (Map.Entry<String, String> e : gd.updates.entrySet()) {
                setField(ctx, e.getKey(), e.getValue());
                audit.add("CONTEXT_UPDATE", stepName, e.getKey() + " updated to: " + e.getValue());
            }
            throw new RerunStepException("Rerun requested (with updates)");
        }
        // APPROVE_CONTINUE: no-op
    }

    static void setField(WorkflowContext ctx, String field, String value) {
        switch (field) {
            case "businessProblem": ctx.businessProblem = value; break;
            case "successCriteria": ctx.successCriteria = value; break;
            case "dataSources": ctx.dataSources = value; break;
            case "grain": ctx.grain = value; break;
            case "kpis": ctx.kpis = value; break;
            default: // ignore unknown
        }
    }

    static void writeCsv(Path path, List<Map<String, String>> rows) throws IOException {
        Files.createDirectories(path.getParent());
        if (rows.isEmpty()) {
            Files.writeString(path, "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append("\n");
        for (Map<String, String> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvEscape(row.get(headers.get(i))));
            }
            sb.append("\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String v = s.replace("\"", "\"\"");
        return needsQuotes ? ("\"" + v + "\"") : v;
    }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        AuditLog audit = new AuditLog();
        WorkflowContext ctx = new WorkflowContext("93996");

        try {
            ctx.outputDir = Paths.get("output_93996");
            Files.createDirectories(ctx.outputDir);
            audit.add("INIT", "Workflow", "Output directory set to " + ctx.outputDir.toAbsolutePath());

            List<Step> steps = Arrays.asList(
                    new RequirementsStep(),
                    new PlanningStep(),
                    new IngestionStep(),
                    new TransformationStep(),
                    new MetricsStep(),
                    new ReportStep()
            );
            List<Hook> hooks = Collections.singletonList(new StandardHook());

            Engine engine = new Engine(steps, hooks, audit);
            engine.run(ctx, in);

        } catch (StopWorkflowException sw) {
            System.out.println("\nWorkflow stopped: " + sw.getMessage());
        } catch (Exception e) {
            System.err.println("\nUnexpected failure: " + e.getMessage());
        } finally {
            // Always attempt to write a minimal audit log file for traceability
            try {
                Path auditPath = Paths.get("output_93996").resolve("audit_log.txt");
                Files.createDirectories(auditPath.getParent());
                StringBuilder sb = new StringBuilder();
                for (AuditEvent ev : audit.events()) {
                    sb.append(ev.ts).append("\t").append(ev.type).append("\t").append(ev.step).append("\t").append(ev.message).append("\n");
                }
                Files.writeString(auditPath, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception ignore) {
                // Not required by the story; best-effort.
            }
            in.close();
        }
    }
}
