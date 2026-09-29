/*
 * User Story 109154 - Centene - Update and Test SAS-to-Python Agents for DMS Workbench
 *
 * What it does:
 *   This is a small, runnable Java CLI utility that helps validate “SAS-to-Python agent” output
 *   against sample SAS code that targets Greenplum and Snowflake.
 *
 *   The Azure DevOps user story describes an update/test activity rather than a buildable software
 *   component with a concrete I/O contract. Assumption: the closest useful, buildable tool is a
 *   validator that:
 *     1) Reads a SAS script and a generated Python script,
 *     2) Detects whether the SAS script appears to target Greenplum or Snowflake,
 *     3) Runs lightweight, deterministic checks that the Python script contains expected “markers”
 *        (e.g., connector/library usage, key tokens),
 *     4) Prints a clear PASS/FAIL report suitable for quick agent validation.
 *
 * How to compile:
 *   javac 109154.java
 *
 * How to run:
 *   java 109154 --sas sample.sas --python generated.py
 *   java 109154 --sas sample.sas --python generated.py --dialect greenplum
 *   java 109154 --sas sample.sas --python generated.py --dialect snowflake
 *
 * Notes:
 *   - No external dependencies are used.
 *   - This utility does NOT attempt to fully parse SAS or Python or execute database connections.
 *   - It simply supports the “validate performance using sample SAS code with Greenplum and
 *     Snowflake integrations” requirement by providing repeatable static checks.
 *
 * Security/Compliance:
 *   - No secrets are read from environment variables or printed.
 *   - Input files are read locally; output is a textual report.
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class 109154 {

    // --- Simple model types ---

    enum Dialect {
        GREENPLUM,
        SNOWFLAKE
    }

    static final class Findings {
        final List<String> passes = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        boolean ok() {
            return failures.isEmpty();
        }
    }

    // --- Entry point ---

    public static void main(String[] args) {
        // Minimal argument parsing as required; no excessive CLI framework.
        Map<String, String> opts = parseArgs(args);

        String sasPath = opts.get("--sas");
        String pyPath = opts.get("--python");
        String dialectOpt = opts.get("--dialect");

        if (sasPath == null || pyPath == null) {
            printUsageAndExit("Missing required arguments: --sas and --python are required.");
            return;
        }

        try {
            String sas = Files.readString(Path.of(sasPath), StandardCharsets.UTF_8);
            String py = Files.readString(Path.of(pyPath), StandardCharsets.UTF_8);

            Dialect dialect;
            if (dialectOpt != null) {
                dialect = parseDialect(dialectOpt);
            } else {
                dialect = detectDialectFromSas(sas);
            }

            Findings findings = validate(sas, py, dialect);
            printReport(sasPath, pyPath, dialect, findings);

            // Keep behavior simple; do not introduce exit-code taxonomy beyond pass/fail.
            if (!findings.ok()) {
                System.exit(1);
            }

        } catch (IOException e) {
            // Only graceful handling relevant to the program: file read failure.
            System.err.println("ERROR: Unable to read input file(s): " + e.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    // --- Core validation ---

    static Findings validate(String sas, String py, Dialect dialect) {
        Objects.requireNonNull(sas, "sas");
        Objects.requireNonNull(py, "py");
        Objects.requireNonNull(dialect, "dialect");

        Findings f = new Findings();

        // Normalize for token checks.
        String sasNorm = normalize(sas);
        String pyNorm = normalize(py);

        // Generic checks: ensure Python has some kind of data I/O semantics.
        requireAny(f, pyNorm,
                "Python appears to read data (expected read_* / cursor execute / SELECT)",
                Arrays.asList("read_", "execute(", "select ", "cursor", "fetch"),
                "Python does not appear to include data read/query logic.");

        // Detect if SAS is “SQL-y” as common for DB pushdown.
        if (sasNorm.contains("proc sql") || sasNorm.contains("create table") || sasNorm.contains("select")) {
            f.passes.add("SAS contains SQL constructs (PROC SQL / SELECT), suitable for DB integration testing.");
        } else {
            f.warnings.add("SAS does not strongly indicate PROC SQL usage; validation is limited to token checks.");
        }

        // Dialect-specific checks.
        switch (dialect) {
            case GREENPLUM -> validateGreenplum(sasNorm, pyNorm, f);
            case SNOWFLAKE -> validateSnowflake(sasNorm, pyNorm, f);
        }

        // Minimal “performance validation” proxy: Python should not contain obviously huge row-by-row loops.
        // Assumption: agent output should prefer set-based operations; flag very suspicious constructs.
        if (pyNorm.contains("for row in") && pyNorm.contains("execute(")) {
            f.warnings.add("Python contains a 'for row in' loop near database execution; may indicate row-by-row operations.");
        } else {
            f.passes.add("No obvious row-by-row DB execution pattern detected.");
        }

        return f;
    }

    static void validateGreenplum(String sasNorm, String pyNorm, Findings f) {
        // SAS indicators for Postgres/Greenplum: libname postgres/odbc, explicit host/port/dbname, etc.
        checkAny(f, sasNorm,
                "SAS indicates Greenplum/Postgres connectivity (optional indicator)",
                Arrays.asList("greenplum", "postgres", "postgre", "odbc", "libname"),
                true);

        // Python indicators: psycopg2/sqlalchemy postgres dialect.
        requireAny(f, pyNorm,
                "Python indicates Greenplum/Postgres connectivity (psycopg2 / sqlalchemy postgres)",
                Arrays.asList("psycopg2", "postgresql", "postgres", "sqlalchemy"),
                "Python is missing common Greenplum/Postgres connector markers (psycopg2/postgresql/sqlalchemy)."
        );

        // Query pushdown markers.
        requireAny(f, pyNorm,
                "Python indicates SQL execution (execute / read_sql)",
                Arrays.asList("execute(", "read_sql", "cursor"),
                "Python is missing common SQL execution markers (execute/read_sql/cursor)."
        );
    }

    static void validateSnowflake(String sasNorm, String pyNorm, Findings f) {
        // SAS indicators: libname snow, odbc, snowflake, etc.
        checkAny(f, sasNorm,
                "SAS indicates Snowflake connectivity (optional indicator)",
                Arrays.asList("snowflake", "libname", "odbc"),
                true);

        // Python indicators: snowflake-connector-python or sqlalchemy snowflake dialect.
        requireAny(f, pyNorm,
                "Python indicates Snowflake connectivity (snowflake.connector / snowflake.sqlalchemy / snowpark)",
                Arrays.asList("snowflake", "snowflake.connector", "snowpark", "snowflake.sqlalchemy"),
                "Python is missing common Snowflake connector markers (snowflake.connector/snowpark/snowflake.sqlalchemy)."
        );

        // Typical usage patterns.
        requireAny(f, pyNorm,
                "Python indicates Snowflake connection/cursor usage (connect / cursor / execute)",
                Arrays.asList("connect(", "cursor", "execute("),
                "Python is missing common connection usage markers (connect/cursor/execute)."
        );
    }

    // --- Helpers ---

    static String normalize(String s) {
        // Lowercase and collapse whitespace for robust contains checks.
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static Dialect detectDialectFromSas(String sas) {
        String n = normalize(sas);
        boolean looksSnow = n.contains("snowflake") || n.contains("libname snow") || n.contains("snow ");
        boolean looksGp = n.contains("greenplum") || n.contains("postgres") || n.contains("postgre");

        if (looksSnow && !looksGp) return Dialect.SNOWFLAKE;
        if (looksGp && !looksSnow) return Dialect.GREENPLUM;

        // Assumption (as required): default to Snowflake when ambiguous.
        return Dialect.SNOWFLAKE;
    }

    static Dialect parseDialect(String v) {
        String x = v.trim().toLowerCase(Locale.ROOT);
        return switch (x) {
            case "greenplum", "gp", "postgres", "postgresql" -> Dialect.GREENPLUM;
            case "snowflake", "sf" -> Dialect.SNOWFLAKE;
            default -> throw new IllegalArgumentException("Unsupported --dialect value: " + v + " (use greenplum|snowflake)");
        };
    }

    static void requireAny(Findings f, String haystack, String passMsg, List<String> needles, String failMsg) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                f.passes.add(passMsg + " [matched: '" + n + "']");
                return;
            }
        }
        f.failures.add(failMsg + " Expected one of: " + needles);
    }

    static void checkAny(Findings f, String haystack, String msg, List<String> needles, boolean warningIfMissing) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                f.passes.add(msg + " [matched: '" + n + "']");
                return;
            }
        }
        if (warningIfMissing) {
            f.warnings.add(msg + " (no matching tokens found; continuing).");
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    m.put(a, args[++i]);
                } else {
                    // Flags without values not used in this minimal tool.
                    m.put(a, "true");
                }
            }
        }
        return m;
    }

    static void printReport(String sasPath, String pyPath, Dialect dialect, Findings f) {
        System.out.println("SAS-to-Python Agent Validation Report");
        System.out.println("UserStory: 109154");
        System.out.println("SAS file: " + sasPath);
        System.out.println("Python file: " + pyPath);
        System.out.println("Dialect: " + dialect);
        System.out.println();

        for (String p : f.passes) System.out.println("PASS: " + p);
        for (String w : f.warnings) System.out.println("WARN: " + w);
        for (String x : f.failures) System.out.println("FAIL: " + x);

        System.out.println();
        System.out.println("RESULT: " + (f.ok() ? "PASS" : "FAIL"));

        // Minimal audit log for compliance, per user instruction.
        // Keep it deterministic and non-sensitive.
        System.out.println();
        System.out.println("Audit Log:");
        System.out.println("- Requirement Source: Azure DevOps Work Item 109154");
        System.out.println("- Action: Static validation of generated Python against SAS sample for DB integration markers");
        System.out.println("- Checked Dialect: " + dialect);
        System.out.println("- Timestamp: " + java.time.OffsetDateTime.now());
    }

    static void printUsageAndExit(String error) {
        System.err.println("ERROR: " + error);
        System.err.println();
        System.err.println("Usage:");
        System.err.println("  java 109154 --sas <sample.sas> --python <generated.py> [--dialect greenplum|snowflake]");
        System.err.println();
        System.err.println("Behavior:");
        System.err.println("  Performs lightweight static checks to validate that Python output contains expected");
        System.err.println("  connector/query markers for Greenplum or Snowflake based on provided or detected dialect.");
        System.exit(2);
    }
}
