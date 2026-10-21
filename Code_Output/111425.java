/*
 * Work Item: 111425 (User Story)
 * Title: Implement Comprehensive Unit Test Coverage for All Controller and Service Classes
 *
 * IMPORTANT DELIVERY CONSTRAINT
 * ----------------------------
 * The user story asks for comprehensive JUnit tests across *many* controller/service classes
 * (plus coverage reporting). However, this agent run is constrained to output EXACTLY ONE
 * Java source file. Creating the required test suite would necessarily span multiple files
 * under src/test/java and cannot be fully delivered as a single Java file.
 *
 * Closest-useful single-file deliverable
 * -------------------------------------
 * This standalone Java 26 program scans a checked-out Spring Boot codebase and produces:
 *   1) Inventory/checklist of all Controller and Service classes (AC1).
 *   2) Suggested test-class names/paths that mirror package structure.
 *   3) A "completion report" style summary (counts, exclusions, notes).
 *
 * It does NOT modify the repository, does NOT generate multiple test files, and does NOT
 * fabricate coverage numbers (AC9). It is deterministic and offline.
 *
 * Usage
 * -----
 *   javac 111425.java
 *   java Requirement111425TestInventory /path/to/aava-ds-datapowerai-api
 *
 * Optional flags:
 *   --excludeTestFrameworks   Ignore files under src/test/java (default true).
 *   --includeInnerClasses     Attempt to detect inner @RestController classes (default false).
 *   --json                    Emit machine-readable JSON instead of text.
 *
 * Notes on repo conventions detected from scan (typical for this repo):
 *   - Controllers under: src/main/java/.../controller
 *   - Services under:     src/main/java/.../service and .../service/implementation
 *   - Spring annotations: @RestController, @Controller, @Service
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Requirement111425TestInventory {

    private static final String DEFAULT_SRC_MAIN = "src/main/java";

    // Fast heuristic patterns (keep simple; no external deps).
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.$]+)\\s*;\\s*$");
    private static final Pattern CLASS_PATTERN = Pattern.compile("^\\s*(public\\s+)?(abstract\\s+)?(final\\s+)?(class|interface|record|enum)\\s+([A-Za-z0-9_]+)\\b");

    private static final Pattern SPRING_ANNOTATION_CONTROLLER = Pattern.compile("@RestController\\b|@Controller\\b");
    private static final Pattern SPRING_ANNOTATION_SERVICE = Pattern.compile("@Service\\b");

    // Exclude generated/irrelevant
    private static final Pattern EXCLUDE_PATH_PATTERN = Pattern.compile("/target/|/build/|/.git/|/.idea/|/out/|/node_modules/", Pattern.CASE_INSENSITIVE);

    enum Kind {
        CONTROLLER,
        SERVICE
    }

    static final class Entry {
        final Kind kind;
        final Path file;
        final String pkg;
        final String typeName;
        final boolean springAnnotated;

        Entry(Kind kind, Path file, String pkg, String typeName, boolean springAnnotated) {
            this.kind = kind;
            this.file = file;
            this.pkg = pkg;
            this.typeName = typeName;
            this.springAnnotated = springAnnotated;
        }

        String fqcn() {
            if (pkg == null || pkg.isBlank()) return typeName;
            return pkg + "." + typeName;
        }

        String suggestedTestFqcn() {
            String suffix = "Test";
            return fqcn() + suffix;
        }

        String suggestedTestPath() {
            // Mirror packages under src/test/java
            String rel = (pkg == null ? "" : pkg.replace('.', '/'));
            if (!rel.isBlank()) rel = rel + "/";
            return "src/test/java/" + rel + typeName + "Test.java";
        }
    }

    static final class ScanResult {
        final List<Entry> controllers = new ArrayList<>();
        final List<Entry> services = new ArrayList<>();
        final List<String> exclusions = new ArrayList<>();
        final Map<String, Object> meta = new LinkedHashMap<>();

        int total() {
            return controllers.size() + services.size();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java Requirement111425TestInventory <repoRoot> [--json] [--includeInnerClasses] [--excludeTestFrameworks]");
            System.exit(2);
        }

        Path repoRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        boolean json = hasFlag(args, "--json");
        boolean includeInnerClasses = hasFlag(args, "--includeInnerClasses");
        boolean excludeTests = !hasFlag(args, "--noExcludeTestFrameworks") && true; // default true

        ScanResult result = scan(repoRoot, includeInnerClasses, excludeTests);

        if (json) {
            System.out.println(toJson(result));
        } else {
            System.out.println(toText(result));
        }
    }

    static ScanResult scan(Path repoRoot, boolean includeInnerClasses, boolean excludeTests) throws IOException {
        ScanResult out = new ScanResult();
        out.meta.put("workItem", 111425);
        out.meta.put("generatedAt", Instant.now().toString());
        out.meta.put("repoRoot", repoRoot.toString());

        Path srcMain = repoRoot.resolve(DEFAULT_SRC_MAIN);
        if (!Files.isDirectory(srcMain)) {
            out.exclusions.add("Repository missing " + DEFAULT_SRC_MAIN + "; cannot inventory controllers/services.");
            return out;
        }

        // detect Spring Boot-ish structure
        out.meta.put("detected", Map.of(
                "springBootLike", Files.exists(repoRoot.resolve("pom.xml")) && Files.isDirectory(srcMain),
                "srcMainJava", DEFAULT_SRC_MAIN
        ));

        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String p = dir.toString().replace('\\', '/');
                if (EXCLUDE_PATH_PATTERN.matcher(p).find()) return FileVisitResult.SKIP_SUBTREE;
                if (excludeTests && p.contains("/src/test/")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String p = file.toString().replace('\\', '/');
                if (!p.endsWith(".java")) return FileVisitResult.CONTINUE;
                if (EXCLUDE_PATH_PATTERN.matcher(p).find()) return FileVisitResult.CONTINUE;
                if (excludeTests && p.contains("/src/test/")) return FileVisitResult.CONTINUE;

                try {
                    Entry e = classifyJavaSource(file, includeInnerClasses);
                    if (e != null) {
                        if (e.kind == Kind.CONTROLLER) out.controllers.add(e);
                        else if (e.kind == Kind.SERVICE) out.services.add(e);
                    }
                } catch (Exception ex) {
                    out.exclusions.add("Failed to parse " + file + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }

                return FileVisitResult.CONTINUE;
            }
        };

        Files.walkFileTree(repoRoot, visitor);

        Comparator<Entry> byFqcn = Comparator.comparing(Entry::fqcn);
        out.controllers.sort(byFqcn);
        out.services.sort(byFqcn);

        // add simple counts
        out.meta.put("counts", Map.of(
                "controllers", out.controllers.size(),
                "services", out.services.size(),
                "total", out.total(),
                "exclusions", out.exclusions.size()
        ));

        return out;
    }

    static Entry classifyJavaSource(Path file, boolean includeInnerClasses) throws IOException {
        // Quick file path heuristics first (repo conventions)
        String normalized = file.toString().replace('\\', '/');
        boolean inControllerPkg = normalized.contains("/src/main/java/") && normalized.contains("/controller/");
        boolean inServicePkg = normalized.contains("/src/main/java/") && (normalized.contains("/service/") || normalized.contains("/service/implementation/") || normalized.contains("/service/impl/"));

        // Read as UTF-8; ignore files that are obviously too large
        long size = Files.size(file);
        if (size > 2_000_000) return null;

        String pkg = null;
        String primaryType = null;
        boolean controllerAnno = false;
        boolean serviceAnno = false;

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null) {
                lines++;
                if (lines > 4000) break; // safeguard

                if (pkg == null) {
                    Matcher pm = PACKAGE_PATTERN.matcher(line);
                    if (pm.matches()) pkg = pm.group(1);
                }

                if (!controllerAnno && SPRING_ANNOTATION_CONTROLLER.matcher(line).find()) controllerAnno = true;
                if (!serviceAnno && SPRING_ANNOTATION_SERVICE.matcher(line).find()) serviceAnno = true;

                if (primaryType == null) {
                    Matcher cm = CLASS_PATTERN.matcher(line);
                    if (cm.find()) {
                        primaryType = cm.group(5);
                        if (!includeInnerClasses) {
                            // We stop after first top-level type name heuristic.
                            // Not perfect, but ok for inventory.
                            break;
                        }
                    }
                }
            }
        }

        if (primaryType == null) return null;

        // Final classification rule: spring annotation wins; otherwise path-based fallback
        if (controllerAnno || inControllerPkg) {
            return new Entry(Kind.CONTROLLER, file, pkg, primaryType, controllerAnno);
        }
        if (serviceAnno || inServicePkg) {
            return new Entry(Kind.SERVICE, file, pkg, primaryType, serviceAnno);
        }
        return null;
    }

    static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    static String toText(ScanResult r) {
        StringBuilder sb = new StringBuilder(32_768);
        sb.append("Work Item 111425 Inventory (Controllers & Services)\n");
        sb.append("GeneratedAt: ").append(r.meta.get("generatedAt")).append('\n');
        sb.append("RepoRoot: ").append(r.meta.get("repoRoot")).append('\n');
        sb.append('\n');

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) r.meta.getOrDefault("counts", Map.of());
        sb.append("Counts: controllers=").append(counts.getOrDefault("controllers", r.controllers.size()))
                .append(", services=").append(counts.getOrDefault("services", r.services.size()))
                .append(", total=").append(counts.getOrDefault("total", r.total()))
                .append("\n\n");

        sb.append("AC1 Checklist (each entry => suggested test file)\n");
        sb.append("------------------------------------------------\n");

        sb.append("\n[Controllers] (recommend @WebMvcTest + MockMvc + @MockitoBean)\n");
        for (Entry e : r.controllers) {
            sb.append("- ").append(e.fqcn()).append("\n")
              .append("  file: ").append(rel(e.file, Paths.get((String) r.meta.get("repoRoot")))).append("\n")
              .append("  suggestedTest: ").append(e.suggestedTestPath()).append("\n");
        }

        sb.append("\n[Services] (recommend JUnit Jupiter + Mockito)\n");
        for (Entry e : r.services) {
            sb.append("- ").append(e.fqcn()).append("\n")
              .append("  file: ").append(rel(e.file, Paths.get((String) r.meta.get("repoRoot")))).append("\n")
              .append("  suggestedTest: ").append(e.suggestedTestPath()).append("\n");
        }

        if (!r.exclusions.isEmpty()) {
            sb.append("\nExclusions / Notes\n");
            sb.append("------------------\n");
            for (String ex : r.exclusions) {
                sb.append("- ").append(ex).append('\n');
            }
        }

        sb.append("\nCompletion Report Template (fill in after writing real tests)\n");
        sb.append("------------------------------------------------------------\n");
        sb.append("- Java: 26\n");
        sb.append("- Spring Boot: 4.1.1\n");
        sb.append("- Test framework: JUnit Jupiter + Mockito; MockMvc for controllers\n");
        sb.append("- Created controller tests: ____ / ").append(r.controllers.size()).append('\n');
        sb.append("- Created service tests: ____ / ").append(r.services.size()).append('\n');
        sb.append("- mvn test result: ____\n");
        sb.append("- Coverage (JaCoCo): ____ (do not fabricate; attach report)\n");

        return sb.toString();
    }

    static String rel(Path file, Path repoRoot) {
        try {
            return repoRoot.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }

    // Minimal JSON writer (no external deps). Produces stable key order.
    static String toJson(ScanResult r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", r.meta);
        root.put("controllers", entriesToJson(r.controllers));
        root.put("services", entriesToJson(r.services));
        root.put("exclusions", r.exclusions);
        return jsonStringify(root);
    }

    static List<Map<String, Object>> entriesToJson(List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind.name());
            m.put("fqcn", e.fqcn());
            m.put("file", e.file.toString().replace('\\', '/'));
            m.put("springAnnotated", e.springAnnotated);
            m.put("suggestedTestPath", e.suggestedTestPath());
            out.add(m);
        }
        return out;
    }

    static String jsonStringify(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
        if (obj instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStringify(e.getKey()));
                sb.append(':');
                sb.append(jsonStringify(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (obj instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object v : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStringify(v));
            }
            sb.append(']');
            return sb.toString();
        }
        // fallback
        return "\"" + escapeJson(String.valueOf(obj)) + "\"";
    }

    static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
