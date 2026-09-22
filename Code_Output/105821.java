/*
User Story ID: 105821

1. Requirement Summary
- Clarified Requirement:
  Update JPA entity @Column mappings so entity fields correctly map to database columns when used with the AAVA DataSource Library, preventing runtime column-mapping issues.

- Assumptions (enterprise defaults due to missing specifics):
  1) The story describes a migration/compatibility fix but does not list impacted entities/columns; this deliverable provides a safe, maintainable Java tool that:
     - validates entity @Column names against a required naming format, and
     - can optionally rewrite @Column(name = "...") values in source files in a controlled way.
     This is the closest useful artifact given that the specific entity classes and the AAVA naming rules are not provided.
  2) “Format supported by the AAVA DataSource Library” is interpreted as a deterministic column-naming strategy. Because the actual rules are not provided, the default strategy implemented is:
     - SNAKE_UPPER: Java fieldName -> FIELD_NAME
     - (alternative provided) SNAKE_LOWER: field_name
     - (alternative provided) AS_IS: no change
  3) Entities are annotated with either javax.persistence.* or jakarta.persistence.*. The tool supports both.
  4) The codebase follows standard Java source layout; this utility operates on a provided directory.
  5) Security: No secrets are read; output logs avoid PII; tool only touches .java files under the specified root.
  6) Data-loss risk: Rewrites are opt-in ("--apply"); otherwise it runs in dry-run mode.
  7) Deliverable type: a standalone CLI “entity column mapping validator/rewriter” plus runnable self-tests.

- Placeholder Disclosure:
  - AAVA DataSource Library’s exact required naming rules were not included in the work item. The tool implements configurable naming strategies and a validation mechanism so the project can plug in the exact rule when known.

- Acceptance Criteria / Goal completeness:
  - No explicit acceptance criteria were present; goals are derived from the Description.

2. Acceptance Criteria / Goal Coverage
No Acceptance Criteria Found in the work item.
Goals derived from Description:
1) Update column names and corresponding JPA @Column mappings to follow a supported format to avoid runtime mapping issues. ✅
   - Implemented via:
     - Validation report of non-conforming @Column names.
     - Optional automatic rewrite of @Column(name="...") to a chosen supported format.
     - Runnable demo entities and a main() entrypoint that exercises validation and rewrite.

3. Generated Java Code
*/

package codeoutput.us105821;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone CLI utility to validate and optionally rewrite JPA @Column(name="...") mappings
 * in Java entity source files so they follow a consistent naming format compatible with a
 * configured strategy.
 *
 * Security & safety:
 * - Dry-run by default; use --apply to modify files.
 * - Only processes .java files under the provided --root directory.
 * - Writes a backup file next to the original when applying changes (.bak).
 *
 * Compile:
 *   javac -d out 105821.java
 * Run demo (self-contained):
 *   java -cp out codeoutput.us105821.EntityColumnMappingTool --run-selftest
 *
 * Run on a repository:
 *   java -cp out codeoutput.us105821.EntityColumnMappingTool --root /path/to/src --strategy SNAKE_UPPER --apply
 */
public final class EntityColumnMappingTool {

    public static void main(String[] args) {
        try {
            CliConfig config = CliConfig.parse(args);
            if (config.runSelfTest) {
                SelfTest.run();
                return;
            }

            if (config.rootDir == null) {
                throw new ToolException("Missing required argument: --root <directory> (or run --run-selftest)");
            }

            ColumnNamingStrategy strategy = ColumnNamingStrategy.fromName(config.strategyName)
                    .orElseThrow(() -> new ToolException("Unknown --strategy: " + config.strategyName));

            EntitySourceScanner scanner = new EntitySourceScanner(strategy);
            ScanReport report = scanner.scan(config.rootDir, config.charset);

            ReportRenderer.renderToStdout(report);

            if (config.applyChanges) {
                EntitySourceRewriter rewriter = new EntitySourceRewriter(strategy);
                RewriteReport rewriteReport = rewriter.apply(report, config.charset);
                ReportRenderer.renderRewriteToStdout(rewriteReport);
            } else {
                System.out.println();
                System.out.println("Dry-run mode: no files modified. Re-run with --apply to write changes.");
            }

        } catch (ToolException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("I/O ERROR: " + e.getMessage());
            System.exit(3);
        }
    }

    /** CLI configuration. */
    static final class CliConfig {
        final Path rootDir;
        final boolean applyChanges;
        final boolean runSelfTest;
        final String strategyName;
        final Charset charset;

        private CliConfig(Path rootDir, boolean applyChanges, boolean runSelfTest, String strategyName, Charset charset) {
            this.rootDir = rootDir;
            this.applyChanges = applyChanges;
            this.runSelfTest = runSelfTest;
            this.strategyName = strategyName;
            this.charset = charset;
        }

        static CliConfig parse(String[] args) {
            Path root = null;
            boolean apply = false;
            boolean selftest = false;
            String strategy = "SNAKE_UPPER";
            Charset charset = StandardCharsets.UTF_8;

            for (int i = 0; i < args.length; i++) {
                String a = Objects.requireNonNull(args[i], "arg");
                switch (a) {
                    case "--root":
                        if (i + 1 >= args.length) {
                            throw new ToolException("--root requires a directory path");
                        }
                        root = Paths.get(args[++i]).toAbsolutePath().normalize();
                        break;
                    case "--apply":
                        apply = true;
                        break;
                    case "--dry-run":
                        apply = false;
                        break;
                    case "--strategy":
                        if (i + 1 >= args.length) {
                            throw new ToolException("--strategy requires a value (SNAKE_UPPER|SNAKE_LOWER|AS_IS)");
                        }
                        strategy = args[++i].trim();
                        break;
                    case "--charset":
                        if (i + 1 >= args.length) {
                            throw new ToolException("--charset requires a value (e.g., UTF-8)");
                        }
                        charset = Charset.forName(args[++i].trim());
                        break;
                    case "--run-selftest":
                        selftest = true;
                        break;
                    case "--help":
                    case "-h":
                        printHelpAndExit();
                        break;
                    default:
                        throw new ToolException("Unknown argument: " + a);
                }
            }
            return new CliConfig(root, apply, selftest, strategy, charset);
        }

        private static void printHelpAndExit() {
            System.out.println("EntityColumnMappingTool - validate/rewrite JPA @Column mappings");
            System.out.println();
            System.out.println("Usage:");
            System.out.println("  --root <dir>         Root directory to scan for .java files");
            System.out.println("  --strategy <name>    SNAKE_UPPER (default) | SNAKE_LOWER | AS_IS");
            System.out.println("  --apply              Apply rewrites in-place (creates .bak backup)");
            System.out.println("  --dry-run            Default; only report");
            System.out.println("  --charset <charset>  Default UTF-8");
            System.out.println("  --run-selftest       Run built-in test/demonstration");
            System.exit(0);
        }
    }

    /** Naming strategies for column names. */
    enum ColumnNamingStrategy {
        SNAKE_UPPER {
            @Override
            public String normalize(String javaFieldName) {
                return toSnake(javaFieldName).toUpperCase(Locale.ROOT);
            }

            @Override
            public boolean isCompliant(String columnName, String javaFieldName) {
                return Objects.equals(columnName, normalize(javaFieldName));
            }
        },
        SNAKE_LOWER {
            @Override
            public String normalize(String javaFieldName) {
                return toSnake(javaFieldName).toLowerCase(Locale.ROOT);
            }

            @Override
            public boolean isCompliant(String columnName, String javaFieldName) {
                return Objects.equals(columnName, normalize(javaFieldName));
            }
        },
        AS_IS {
            @Override
            public String normalize(String javaFieldName) {
                return javaFieldName;
            }

            @Override
            public boolean isCompliant(String columnName, String javaFieldName) {
                return Objects.equals(columnName, javaFieldName);
            }
        };

        public abstract String normalize(String javaFieldName);

        public abstract boolean isCompliant(String columnName, String javaFieldName);

        static Optional<ColumnNamingStrategy> fromName(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String n = name.trim().toUpperCase(Locale.ROOT);
            for (ColumnNamingStrategy s : values()) {
                if (s.name().equals(n)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }

        static String toSnake(String camelOrMixed) {
            if (camelOrMixed == null || camelOrMixed.isBlank()) {
                return "";
            }
            String s = camelOrMixed.trim();
            StringBuilder out = new StringBuilder(s.length() + 8);
            char prev = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '-') {
                    c = '_';
                }
                boolean isUpper = Character.isUpperCase(c);
                boolean isLower = Character.isLowerCase(c);
                boolean isDigit = Character.isDigit(c);
                boolean prevLower = prev != 0 && Character.isLowerCase(prev);
                boolean prevDigit = prev != 0 && Character.isDigit(prev);
                boolean prevUpper = prev != 0 && Character.isUpperCase(prev);

                if (c == '_') {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                        out.append('_');
                    }
                    prev = c;
                    continue;
                }

                if (out.length() > 0) {
                    if (isUpper && (prevLower || prevDigit)) {
                        out.append('_');
                    } else if (isDigit && (prevLower || prevUpper)) {
                        out.append('_');
                    } else if (isLower && prevUpper) {
                        // Handle "URLValue" -> "URL_VALUE" (split before last upper when next is lower)
                        char prevPrev = (out.length() >= 2) ? out.charAt(out.length() - 2) : 0;
                        if (prevPrev != '_' && Character.isUpperCase(prev) && Character.isUpperCase(prevPrev)) {
                            out.insert(out.length() - 1, '_');
                        }
                    }
                }
                out.append(c);
                prev = c;
            }
            // Normalize multiple underscores
            return out.toString().replaceAll("_+", "_");
        }
    }

    /** A finding about a specific field/@Column mapping. */
    static final class ColumnFinding {
        final Path file;
        final String className;
        final String fieldName;
        final String annotationType;
        final String currentColumnName;
        final String expectedColumnName;
        final int lineNumber;
        final String originalLine;

        ColumnFinding(Path file,
                      String className,
                      String fieldName,
                      String annotationType,
                      String currentColumnName,
                      String expectedColumnName,
                      int lineNumber,
                      String originalLine) {
            this.file = file;
            this.className = className;
            this.fieldName = fieldName;
            this.annotationType = annotationType;
            this.currentColumnName = currentColumnName;
            this.expectedColumnName = expectedColumnName;
            this.lineNumber = lineNumber;
            this.originalLine = originalLine;
        }

        boolean isCompliant() {
            return Objects.equals(currentColumnName, expectedColumnName);
        }
    }

    /** Scan report across files. */
    static final class ScanReport {
        final ColumnNamingStrategy strategy;
        final Instant scannedAt;
        final Path root;
        final Map<Path, List<ColumnFinding>> findingsByFile;
        final List<String> warnings;

        ScanReport(ColumnNamingStrategy strategy, Instant scannedAt, Path root,
                   Map<Path, List<ColumnFinding>> findingsByFile,
                   List<String> warnings) {
            this.strategy = strategy;
            this.scannedAt = scannedAt;
            this.root = root;
            this.findingsByFile = findingsByFile;
            this.warnings = warnings;
        }

        List<ColumnFinding> allFindings() {
            List<ColumnFinding> all = new ArrayList<>();
            for (List<ColumnFinding> fs : findingsByFile.values()) {
                all.addAll(fs);
            }
            return all;
        }

        long nonCompliantCount() {
            return allFindings().stream().filter(f -> !f.isCompliant()).count();
        }

        long compliantCount() {
            return allFindings().stream().filter(ColumnFinding::isCompliant).count();
        }
    }

    /**
     * Scans Java source files for JPA entity fields that have @Column(name="...") and checks
     * the column name against the configured strategy.
     */
    static final class EntitySourceScanner {
        private final ColumnNamingStrategy strategy;

        EntitySourceScanner(ColumnNamingStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
        }

        ScanReport scan(Path root, Charset charset) throws IOException {
            if (root == null || !Files.isDirectory(root)) {
                throw new ToolException("--root must be an existing directory: " + root);
            }

            Map<Path, List<ColumnFinding>> byFile = new TreeMap<>();
            List<String> warnings = new ArrayList<>();

            Files.walkFileTree(root, new JavaFileVisitor(path -> {
                try {
                    List<ColumnFinding> findings = scanFile(path, charset);
                    if (!findings.isEmpty()) {
                        byFile.put(path, findings);
                    }
                } catch (IOException e) {
                    warnings.add("Failed reading " + path + ": " + e.getMessage());
                }
            }));

            return new ScanReport(strategy, Instant.now(), root, byFile, warnings);
        }

        private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
        private static final Pattern COLUMN_ANNOTATION_PATTERN = Pattern.compile("@(?:javax\\.persistence\\.|jakarta\\.persistence\\.)?Column\\s*\\(([^)]*)\\)");
        private static final Pattern COLUMN_NAME_ATTR_PATTERN = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
        private static final Pattern FIELD_PATTERN = Pattern.compile("\\b(private|protected|public)\\s+([A-Za-z0-9_$.<>\\[\\]]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(=|;)");

        List<ColumnFinding> scanFile(Path file, Charset charset) throws IOException {
            if (!file.toString().endsWith(".java")) {
                return Collections.emptyList();
            }

            List<String> lines = Files.readAllLines(file, charset);
            String currentClass = "<unknown>";

            // Track last @Column line before a field
            PendingColumn pending = null;
            List<ColumnFinding> out = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher cls = CLASS_PATTERN.matcher(line);
                if (cls.find()) {
                    currentClass = cls.group(1);
                }

                Matcher col = COLUMN_ANNOTATION_PATTERN.matcher(line);
                if (col.find()) {
                    String args = col.group(1);
                    Matcher name = COLUMN_NAME_ATTR_PATTERN.matcher(args);
                    if (name.find()) {
                        pending = new PendingColumn(i + 1, line, name.group(1));
                    } else {
                        // @Column without explicit name isn't actionable here.
                        pending = null;
                    }
                    continue;
                }

                Matcher field = FIELD_PATTERN.matcher(line);
                if (field.find() && pending != null) {
                    String fieldName = field.group(3);
                    String currentCol = pending.columnName;
                    String expected = strategy.normalize(fieldName);
                    out.add(new ColumnFinding(file, currentClass, fieldName, "@Column", currentCol, expected, pending.lineNumber, pending.originalLine));
                    pending = null;
                }

                // reset pending if we hit other annotations or empty lines for too long? Keep simple:
                if (line.trim().isEmpty()) {
                    // keep pending
                } else if (line.trim().startsWith("@") && pending != null && !line.contains("@Column")) {
                    // still keep pending - could have multiple annotations
                }
            }

            return out;
        }

        static final class PendingColumn {
            final int lineNumber;
            final String originalLine;
            final String columnName;

            PendingColumn(int lineNumber, String originalLine, String columnName) {
                this.lineNumber = lineNumber;
                this.originalLine = originalLine;
                this.columnName = columnName;
            }
        }
    }

    /** Applies rewrites for non-compliant findings. */
    static final class EntitySourceRewriter {
        private final ColumnNamingStrategy strategy;

        EntitySourceRewriter(ColumnNamingStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
        }

        RewriteReport apply(ScanReport report, Charset charset) throws IOException {
            Map<Path, FileRewriteResult> results = new LinkedHashMap<>();

            for (Map.Entry<Path, List<ColumnFinding>> e : report.findingsByFile.entrySet()) {
                Path file = e.getKey();
                List<ColumnFinding> findings = e.getValue();

                List<ColumnFinding> nonCompliant = new ArrayList<>();
                for (ColumnFinding f : findings) {
                    if (!f.isCompliant()) {
                        nonCompliant.add(f);
                    }
                }
                if (nonCompliant.isEmpty()) {
                    continue;
                }

                FileRewriteResult r = rewriteFile(file, nonCompliant, charset);
                results.put(file, r);
            }

            return new RewriteReport(Instant.now(), results);
        }

        private static final Pattern COLUMN_LINE_PATTERN = Pattern.compile("(@(?:javax\\.persistence\\.|jakarta\\.persistence\\.)?Column\\s*\\([^)]*\\bname\\s*=\\s*\")([^\"]+)(\"[^)]*\\))");

        private FileRewriteResult rewriteFile(Path file, List<ColumnFinding> nonCompliant, Charset charset) throws IOException {
            List<String> lines = Files.readAllLines(file, charset);

            Map<Integer, ColumnFinding> byLine = new HashMap<>();
            for (ColumnFinding f : nonCompliant) {
                // finding.lineNumber is 1-based
                byLine.put(f.lineNumber, f);
            }

            int changed = 0;
            List<String> newLines = new ArrayList<>(lines.size());

            for (int i = 0; i < lines.size(); i++) {
                int lineNo = i + 1;
                String line = lines.get(i);
                ColumnFinding finding = byLine.get(lineNo);
                if (finding == null) {
                    newLines.add(line);
                    continue;
                }

                Matcher m = COLUMN_LINE_PATTERN.matcher(line);
                if (!m.find()) {
                    // Defensive: if pattern doesn't match, do not alter.
                    newLines.add(line);
                    continue;
                }

                String before = m.group(1);
                String current = m.group(2);
                String after = m.group(3);

                if (!Objects.equals(current, finding.currentColumnName)) {
                    // The file changed since scan; avoid risky rewrite.
                    newLines.add(line);
                    continue;
                }

                String updated = before + finding.expectedColumnName + after;
                newLines.add(updated);
                changed++;
            }

            if (changed == 0) {
                return new FileRewriteResult(file, false, "No changes applied (content drift or no matches)", null, null);
            }

            // Write backup and replace atomically best-effort
            Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
            Files.writeString(backup, String.join(System.lineSeparator(), lines) + System.lineSeparator(), charset);

            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, String.join(System.lineSeparator(), newLines) + System.lineSeparator(), charset);

            String beforeHash = sha256OfLines(lines, charset);
            String afterHash = sha256OfLines(newLines, charset);

            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return new FileRewriteResult(file, true, "Updated " + changed + " @Column mapping(s)", beforeHash, afterHash);
        }

        private static String sha256OfLines(List<String> lines, Charset charset) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (String line : lines) {
                    md.update(line.getBytes(charset));
                    md.update((byte) '\n');
                }
                byte[] digest = md.digest();
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(String.format(Locale.ROOT, "%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new ToolException("SHA-256 not available", e);
            }
        }
    }

    static final class RewriteReport {
        final Instant rewrittenAt;
        final Map<Path, FileRewriteResult> results;

        RewriteReport(Instant rewrittenAt, Map<Path, FileRewriteResult> results) {
            this.rewrittenAt = rewrittenAt;
            this.results = results;
        }
    }

    static final class FileRewriteResult {
        final Path file;
        final boolean changed;
        final String message;
        final String beforeSha256;
        final String afterSha256;

        FileRewriteResult(Path file, boolean changed, String message, String beforeSha256, String afterSha256) {
            this.file = file;
            this.changed = changed;
            this.message = message;
            this.beforeSha256 = beforeSha256;
            this.afterSha256 = afterSha256;
        }
    }

    /** Renders scan/rewrite results to an observable artifact: console output. */
    static final class ReportRenderer {
        static void renderToStdout(ScanReport report) {
            System.out.println("Entity Column Mapping Report");
            System.out.println("Root: " + report.root);
            System.out.println("Strategy: " + report.strategy.name());
            System.out.println("Scanned at: " + report.scannedAt);
            System.out.println();

            if (!report.warnings.isEmpty()) {
                System.out.println("Warnings:");
                for (String w : report.warnings) {
                    System.out.println("  - " + w);
                }
                System.out.println();
            }

            if (report.findingsByFile.isEmpty()) {
                System.out.println("No @Column(name=\"...\") mappings found.");
                return;
            }

            long nonCompliant = report.nonCompliantCount();
            long compliant = report.compliantCount();
            System.out.println("Findings: " + (compliant + nonCompliant) + " mapping(s)");
            System.out.println("Compliant: " + compliant);
            System.out.println("Non-compliant: " + nonCompliant);
            System.out.println();

            for (Map.Entry<Path, List<ColumnFinding>> e : report.findingsByFile.entrySet()) {
                Path file = e.getKey();
                List<ColumnFinding> findings = e.getValue();
                System.out.println("File: " + file);
                for (ColumnFinding f : findings) {
                    if (!f.isCompliant()) {
                        System.out.println("  [NON-COMPLIANT] " + f.className + "." + f.fieldName + " line " + f.lineNumber);
                        System.out.println("    current : " + f.currentColumnName);
                        System.out.println("    expected: " + f.expectedColumnName);
                    }
                }
                System.out.println();
            }
        }

        static void renderRewriteToStdout(RewriteReport report) {
            System.out.println();
            System.out.println("Rewrite Report");
            System.out.println("Rewritten at: " + report.rewrittenAt);
            if (report.results.isEmpty()) {
                System.out.println("No files changed.");
                return;
            }
            for (FileRewriteResult r : report.results.values()) {
                System.out.println("File: " + r.file);
                System.out.println("  changed: " + r.changed);
                System.out.println("  message: " + r.message);
                if (r.beforeSha256 != null && r.afterSha256 != null) {
                    System.out.println("  beforeSha256: " + r.beforeSha256);
                    System.out.println("  afterSha256 : " + r.afterSha256);
                }
            }
        }
    }

    /** Strict, typed tool exception for consistent error handling. */
    static final class ToolException extends RuntimeException {
        ToolException(String message) {
            super(message);
        }

        ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** File visitor that visits only .java files and calls a callback. */
    static final class JavaFileVisitor implements FileVisitor<Path> {
        interface FileCallback {
            void onFile(Path path);
        }

        private final FileCallback callback;

        JavaFileVisitor(FileCallback callback) {
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.getFileName() != null) {
                String name = dir.getFileName().toString();
                if (name.equals(".git") || name.equals("target") || name.equals("build") || name.equals("out")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.toString().endsWith(".java")) {
                callback.onFile(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Self-test harness that creates temporary sample entity files, scans them, and applies
     * rewrites to demonstrate end-to-end behavior.
     */
    static final class SelfTest {
        static void run() throws IOException {
            System.out.println("Running self-test...");

            Path tempRoot = Files.createTempDirectory("us105821-entity-mapping");
            Path src = tempRoot.resolve("src");
            Files.createDirectories(src);

            Path entity1 = src.resolve("CustomerEntity.java");
            Path entity2 = src.resolve("OrderEntity.java");

            Files.writeString(entity1, DemoEntities.customerEntitySource(), StandardCharsets.UTF_8);
            Files.writeString(entity2, DemoEntities.orderEntitySource(), StandardCharsets.UTF_8);

            ColumnNamingStrategy strategy = ColumnNamingStrategy.SNAKE_UPPER;
            EntitySourceScanner scanner = new EntitySourceScanner(strategy);
            ScanReport report = scanner.scan(tempRoot, StandardCharsets.UTF_8);
            ReportRenderer.renderToStdout(report);

            if (report.nonCompliantCount() == 0) {
                throw new ToolException("Self-test expected non-compliant mappings but found none.");
            }

            EntitySourceRewriter rewriter = new EntitySourceRewriter(strategy);
            RewriteReport rewriteReport = rewriter.apply(report, StandardCharsets.UTF_8);
            ReportRenderer.renderRewriteToStdout(rewriteReport);

            // Re-scan to ensure compliance
            ScanReport report2 = scanner.scan(tempRoot, StandardCharsets.UTF_8);
            if (report2.nonCompliantCount() != 0) {
                throw new ToolException("Self-test failed: expected 0 non-compliant after rewrite, got " + report2.nonCompliantCount());
            }

            System.out.println();
            System.out.println("Self-test PASSED. Temp data at: " + tempRoot);
        }
    }

    /** Demo entity sources for self-test. */
    static final class DemoEntities {
        static String customerEntitySource() {
            return String.join(System.lineSeparator(),
                    "package demo;",
                    "",
                    "import javax.persistence.Column;",
                    "import javax.persistence.Entity;",
                    "import javax.persistence.Id;",
                    "",
                    "@Entity",
                    "public class CustomerEntity {",
                    "    @Id",
                    "    private String id;",
                    "",
                    "    @Column(name=\"customerName\")",
                    "    private String customerName;",
                    "",
                    "    @Column(name=\"CREATED_AT\")",
                    "    private String createdAt;",
                    "}",
                    "");
        }

        static String orderEntitySource() {
            return String.join(System.lineSeparator(),
                    "package demo;",
                    "",
                    "import jakarta.persistence.Column;",
                    "import jakarta.persistence.Entity;",
                    "import jakarta.persistence.Id;",
                    "",
                    "@Entity",
                    "public class OrderEntity {",
                    "    @Id",
                    "    private String id;",
                    "",
                    "    @Column(name = \"orderTotal\")",
                    "    private String orderTotal;",
                    "",
                    "    @Column(name = \"ORDER_DATE\")",
                    "    private String orderDate;",
                    "}",
                    "");
        }
    }
}

/*
4. Code Documentation
Main deliverable: codeoutput.us105821.EntityColumnMappingTool

Key components:
- EntityColumnMappingTool.main:
  Parses CLI args, scans Java source for @Column(name="...") followed by a field, reports compliance,
  and optionally rewrites non-compliant mappings in-place.

- ColumnNamingStrategy:
  Provides configurable naming rules. Default is SNAKE_UPPER, converting fieldName -> FIELD_NAME.
  (The AAVA DataSource Library rule was not specified; this is the safe enterprise default with configuration.)

- EntitySourceScanner:
  Walks .java files under --root and extracts mappings using regex for:
  - class name
  - @Column(name="...")
  - field declarations
  It produces a ScanReport with findings.

- EntitySourceRewriter:
  Applies changes only for non-compliant findings.
  Safety features:
  - Creates .bak backup
  - Writes to .tmp and atomically moves
  - Avoids rewrite if file content drift is detected (current name mismatch)
  - Produces SHA-256 hashes for before/after auditing.

- SelfTest:
  End-to-end runnable path that creates temp demo entity files (javax/jakarta), scans, rewrites, and rescans.
  This ensures goals are exercised with working code.

Dependencies:
- None (JDK only).

Acceptance Criteria / Goal Coverage summary:
- No ACs existed. Description goal implemented ✅ via validation + optional rewrite + runnable self-test.

5. Audit Log
Requirement (User Story 105821) fetched from Azure DevOps and code generated on 2026-09-22 by agent.
*/
