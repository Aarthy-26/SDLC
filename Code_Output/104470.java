/*
 * Work Item ID: 104470
 * Title: App Version Management / Version History – Backend
 * State: Ready for QA
 * Area/Iteration: AAVA\\AAVA - Data Studio\\DPAI / AAVA\\PI4\\Sprint 3
 *
 * What this program does
 * ----------------------
 * This is a single-file, runnable Java program that implements a minimal, self-contained backend-like
 * simulator for application version history and restore operations as described in the work item.
 *
 * It exposes two operations via command line:
 *   1) GET  /api/apps/{appId}/versions
 *   2) POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * The program persists state in a local JSONL (JSON-per-line) file so that versions and audit entries
 * survive across runs, while keeping the deliverable as a single Java file with no external deps.
 *
 * Compile
 * -------
 *   javac 104470.java
 *
 * Run
 * ---
 *   java AppVersionManagementSimulator <command> [args]
 *
 * Commands
 * --------
 *   seed <appId>
 *       Creates a sample app with a few versions (if it does not already exist).
 *
 *   get-versions <appId> --user <username> [--roles <commaSeparatedRoles>]
 *       Simulates: GET /api/apps/{appId}/versions
 *
 *   restore <appId> <versionNumber> --user <username> [--roles <commaSeparatedRoles>]
 *       Simulates: POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * Output
 * ------
 * Prints a JSON response to stdout.
 *
 * Assumptions (due to ambiguity in the story)
 * -------------------------------------------
 * - Because the user story is about Spring Boot APIs, but the task requires a single runnable Java program
 *   with no extra setup, this implementation simulates the API behavior and response structures in-process.
 * - Authorization model: a user may read versions if they have role ROLE_USER or ROLE_READ_ONLY or ROLE_PROJECT_ADMIN
 *   or ROLE_TOOL_ADMIN. A user may restore only if they have ROLE_PROJECT_ADMIN or ROLE_TOOL_ADMIN.
 * - Version numbers are integers >= 1.
 * - “Invalid/deleted/inaccessible versions” are represented by flags on versions (deleted, accessible).
 * - Concurrency: restore uses a JVM-level lock per appId to prevent concurrent restores.
 *
 * Requires Java 17+
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class AppVersionManagementSimulator {

    // ----------------------------
    // Data model
    // ----------------------------

    static final class App {
        final String appId;
        final Map<Integer, AppVersion> versionsByNumber = new HashMap<>();
        int currentVersion;

        App(String appId) {
            this.appId = appId;
        }
    }

    static final class AppVersion {
        final int versionNumber;
        final Instant createdAt;
        final String createdBy;
        boolean active;
        boolean deleted;
        boolean accessible;
        String payload; // application configuration/data snapshot (simplified)

        AppVersion(int versionNumber, Instant createdAt, String createdBy, boolean active,
                   boolean deleted, boolean accessible, String payload) {
            this.versionNumber = versionNumber;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.active = active;
            this.deleted = deleted;
            this.accessible = accessible;
            this.payload = payload;
        }
    }

    static final class AuditEntry {
        final Instant timestamp;
        final String appId;
        final int versionNumber;
        final String user;
        final String status; // SUCCESS / FAILURE
        final String failureReason; // nullable

        AuditEntry(Instant timestamp, String appId, int versionNumber, String user, String status, String failureReason) {
            this.timestamp = timestamp;
            this.appId = appId;
            this.versionNumber = versionNumber;
            this.user = user;
            this.status = status;
            this.failureReason = failureReason;
        }
    }

    // ----------------------------
    // Persistence (simple JSONL)
    // ----------------------------

    private static final Path STORE_PATH = Paths.get("app-version-store.jsonl");

    private static final String REC_APP = "APP";
    private static final String REC_VERSION = "VERSION";
    private static final String REC_AUDIT = "AUDIT";

    // In-memory store
    private final Map<String, App> apps = new HashMap<>();
    private final List<AuditEntry> audits = new ArrayList<>();

    // Per-app locks for concurrent restore operations
    private static final ConcurrentHashMap<String, Object> APP_LOCKS = new ConcurrentHashMap<>();

    // ----------------------------
    // Entry point
    // ----------------------------

    public static void main(String[] args) {
        try {
            new AppVersionManagementSimulator().run(args);
        } catch (Exception e) {
            // For this tool, unexpected exceptions become a 5xx-like response.
            System.out.println(Json.obj(
                    "success", false,
                    "status", 500,
                    "message", "Server failure",
                    "error", Json.obj(
                            "type", e.getClass().getSimpleName(),
                            "details", safeMessage(e)
                    )
            ));
        }
    }

    private void run(String[] args) throws IOException {
        loadStore();

        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return;
        }

        String cmd = args[0];
        switch (cmd) {
            case "seed" -> handleSeed(args);
            case "get-versions" -> handleGetVersions(args);
            case "restore" -> handleRestore(args);
            default -> {
                System.out.println(Json.obj(
                        "success", false,
                        "status", 400,
                        "message", "Unknown command: " + cmd
                ));
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  javac 104470.java
                  java AppVersionManagementSimulator <command> [args]

                Commands:
                  seed <appId>

                  get-versions <appId> --user <username> [--roles <commaSeparatedRoles>]

                  restore <appId> <versionNumber> --user <username> [--roles <commaSeparatedRoles>]

                Examples:
                  java AppVersionManagementSimulator seed app-123
                  java AppVersionManagementSimulator get-versions app-123 --user alice --roles ROLE_USER
                  java AppVersionManagementSimulator restore app-123 2 --user neha --roles ROLE_PROJECT_ADMIN
                """);
    }

    // ----------------------------
    // Command handlers
    // ----------------------------

    private void handleSeed(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println(Json.obj("success", false, "status", 400, "message", "seed requires <appId>"));
            return;
        }
        String appId = args[1];
        if (appId.isBlank()) {
            System.out.println(Json.obj("success", false, "status", 400, "message", "appId cannot be blank"));
            return;
        }

        if (apps.containsKey(appId)) {
            System.out.println(Json.obj("success", true, "status", 200, "message", "App already exists", "data", Json.obj("appId", appId)));
            return;
        }

        App app = new App(appId);
        // Create 3 versions; version 3 active.
        Instant now = Instant.now();
        app.versionsByNumber.put(1, new AppVersion(1, now.minusSeconds(3600), "seed", false, false, true, "{\"ui\":\"v1\"}"));
        app.versionsByNumber.put(2, new AppVersion(2, now.minusSeconds(1800), "seed", false, false, true, "{\"ui\":\"v2\"}"));
        app.versionsByNumber.put(3, new AppVersion(3, now.minusSeconds(600), "seed", true, false, true, "{\"ui\":\"v3\"}"));
        app.currentVersion = 3;
        apps.put(appId, app);

        persistAll();

        System.out.println(Json.obj(
                "success", true,
                "status", 201,
                "message", "Seeded app with versions",
                "data", Json.obj(
                        "appId", appId,
                        "currentVersion", app.currentVersion,
                        "versionCount", app.versionsByNumber.size()
                )
        ));
    }

    private void handleGetVersions(String[] args) {
        // get-versions <appId> --user <username> [--roles <roles>]
        if (args.length < 4) {
            System.out.println(Json.obj("success", false, "status", 400, "message", "get-versions requires <appId> --user <username>"));
            return;
        }

        String appId = args[1];
        ParsedAuth auth = parseAuth(Arrays.copyOfRange(args, 2, args.length));
        if (!auth.ok) {
            System.out.println(auth.errorJson);
            return;
        }

        // Authorization
        if (!canReadVersions(auth.roles)) {
            System.out.println(error(403, "Forbidden - Access is denied", "USER_NOT_AUTHORIZED"));
            return;
        }

        App app = apps.get(appId);
        if (app == null) {
            System.out.println(error(404, "Application not found", "APP_NOT_FOUND"));
            return;
        }

        // Retrieve valid versions only
        List<AppVersion> list = new ArrayList<>();
        for (AppVersion v : app.versionsByNumber.values()) {
            if (v.deleted) continue;
            if (!v.accessible) continue;
            list.add(v);
        }

        // Sort latest first
        list.sort(Comparator.comparingInt((AppVersion v) -> v.versionNumber).reversed());

        if (list.isEmpty()) {
            System.out.println(Json.obj(
                    "success", true,
                    "status", 200,
                    "message", "No versions available",
                    "data", Json.arr()
            ));
            return;
        }

        List<String> versionsJson = new ArrayList<>();
        for (AppVersion v : list) {
            versionsJson.add(Json.obj(
                    "versionNumber", v.versionNumber,
                    "createdAt", v.createdAt.toString(),
                    "createdBy", v.createdBy,
                    "active", v.active
            ));
        }

        System.out.println(Json.obj(
                "success", true,
                "status", 200,
                "message", "Versions retrieved",
                "data", Json.arr(versionsJson)
        ));
    }

    private void handleRestore(String[] args) throws IOException {
        // restore <appId> <versionNumber> --user <username> [--roles <roles>]
        if (args.length < 5) {
            System.out.println(Json.obj("success", false, "status", 400, "message", "restore requires <appId> <versionNumber> --user <username>"));
            return;
        }

        String appId = args[1];
        Integer versionNumber;
        try {
            versionNumber = Integer.parseInt(args[2]);
        } catch (NumberFormatException nfe) {
            System.out.println(error(400, "Invalid versionNumber", "INVALID_VERSION_NUMBER"));
            return;
        }
        if (versionNumber < 1) {
            System.out.println(error(400, "Invalid versionNumber", "INVALID_VERSION_NUMBER"));
            return;
        }

        ParsedAuth auth = parseAuth(Arrays.copyOfRange(args, 3, args.length));
        if (!auth.ok) {
            System.out.println(auth.errorJson);
            return;
        }

        // Authorization
        if (!canRestore(auth.roles)) {
            // 401/403 requested; here we assume authenticated user but insufficient role => 403
            auditRestore(appId, versionNumber, auth.user, "FAILURE", "USER_NOT_AUTHORIZED");
            persistAll();
            System.out.println(error(403, "Forbidden - Access is denied", "USER_NOT_AUTHORIZED"));
            return;
        }

        App app = apps.get(appId);
        if (app == null) {
            auditRestore(appId, versionNumber, auth.user, "FAILURE", "APP_NOT_FOUND");
            persistAll();
            System.out.println(error(404, "Application not found", "APP_NOT_FOUND"));
            return;
        }

        Object lock = APP_LOCKS.computeIfAbsent(appId, k -> new Object());
        synchronized (lock) {
            // Re-check within lock
            AppVersion target = app.versionsByNumber.get(versionNumber);
            if (target == null) {
                auditRestore(appId, versionNumber, auth.user, "FAILURE", "VERSION_NOT_FOUND");
                persistAll();
                System.out.println(error(404, "Version not found", "VERSION_NOT_FOUND"));
                return;
            }

            if (target.deleted || !target.accessible) {
                auditRestore(appId, versionNumber, auth.user, "FAILURE", "VERSION_INACCESSIBLE");
                persistAll();
                System.out.println(error(409, "Invalid or inaccessible version", "VERSION_INACCESSIBLE"));
                return;
            }

            // Transaction-like behavior: snapshot current state
            int oldCurrent = app.currentVersion;
            Map<Integer, Boolean> oldActiveFlags = new HashMap<>();
            for (AppVersion v : app.versionsByNumber.values()) {
                oldActiveFlags.put(v.versionNumber, v.active);
            }

            try {
                // Restore operation: set selected version active/current
                for (AppVersion v : app.versionsByNumber.values()) {
                    v.active = (v.versionNumber == versionNumber);
                }
                app.currentVersion = versionNumber;

                // Preserve audit/version info: we do not delete versions; we only switch active.
                auditRestore(appId, versionNumber, auth.user, "SUCCESS", null);

                persistAll();

                System.out.println(Json.obj(
                        "success", true,
                        "status", 200,
                        "message", "Restore completed successfully",
                        "data", Json.obj(
                                "appId", appId,
                                "restoredVersion", versionNumber,
                                "previousVersion", oldCurrent,
                                "activeVersion", app.currentVersion
                        )
                ));

            } catch (Exception e) {
                // Rollback
                app.currentVersion = oldCurrent;
                for (AppVersion v : app.versionsByNumber.values()) {
                    Boolean flag = oldActiveFlags.get(v.versionNumber);
                    if (flag != null) v.active = flag;
                }

                auditRestore(appId, versionNumber, auth.user, "FAILURE", "UNEXPECTED_RESTORE_FAILURE");
                persistAll();

                System.out.println(error(500, "Unexpected restoration failure", safeMessage(e)));
            }
        }
    }

    // ----------------------------
    // Authorization helpers
    // ----------------------------

    private static boolean canReadVersions(Set<String> roles) {
        return roles.contains("ROLE_TOOL_ADMIN")
                || roles.contains("ROLE_PROJECT_ADMIN")
                || roles.contains("ROLE_USER")
                || roles.contains("ROLE_READ_ONLY");
    }

    private static boolean canRestore(Set<String> roles) {
        return roles.contains("ROLE_TOOL_ADMIN") || roles.contains("ROLE_PROJECT_ADMIN");
    }

    // ----------------------------
    // Audit
    // ----------------------------

    private void auditRestore(String appId, int versionNumber, String user, String status, String failureReason) {
        audits.add(new AuditEntry(Instant.now(), appId, versionNumber, user, status, failureReason));
    }

    // ----------------------------
    // Store load/save
    // ----------------------------

    private void loadStore() throws IOException {
        apps.clear();
        audits.clear();

        if (!Files.exists(STORE_PATH)) {
            return;
        }

        try (BufferedReader br = Files.newBufferedReader(STORE_PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Map<String, String> obj = Json.parseFlatObject(line);
                String type = obj.get("type");
                if (REC_APP.equals(type)) {
                    String appId = obj.get("appId");
                    int currentVersion = Integer.parseInt(obj.getOrDefault("currentVersion", "0"));
                    App app = apps.computeIfAbsent(appId, App::new);
                    app.currentVersion = currentVersion;
                } else if (REC_VERSION.equals(type)) {
                    String appId = obj.get("appId");
                    int versionNumber = Integer.parseInt(obj.get("versionNumber"));
                    Instant createdAt = Instant.parse(obj.get("createdAt"));
                    String createdBy = obj.getOrDefault("createdBy", "unknown");
                    boolean active = Boolean.parseBoolean(obj.getOrDefault("active", "false"));
                    boolean deleted = Boolean.parseBoolean(obj.getOrDefault("deleted", "false"));
                    boolean accessible = Boolean.parseBoolean(obj.getOrDefault("accessible", "true"));
                    String payload = obj.getOrDefault("payload", "{}");

                    App app = apps.computeIfAbsent(appId, App::new);
                    app.versionsByNumber.put(versionNumber, new AppVersion(versionNumber, createdAt, createdBy, active, deleted, accessible, payload));
                    if (active) app.currentVersion = versionNumber;
                } else if (REC_AUDIT.equals(type)) {
                    Instant ts = Instant.parse(obj.get("timestamp"));
                    String appId = obj.get("appId");
                    int vn = Integer.parseInt(obj.get("versionNumber"));
                    String user = obj.getOrDefault("user", "unknown");
                    String status = obj.getOrDefault("status", "UNKNOWN");
                    String fr = obj.get("failureReason");
                    audits.add(new AuditEntry(ts, appId, vn, user, status, fr));
                }
            }
        }

        // Normalize: ensure exactly one active matches currentVersion where possible
        for (App app : apps.values()) {
            if (app.currentVersion > 0) {
                for (AppVersion v : app.versionsByNumber.values()) {
                    v.active = (v.versionNumber == app.currentVersion);
                }
            }
        }
    }

    private void persistAll() throws IOException {
        // Overwrite file with full current state
        try (BufferedWriter bw = Files.newBufferedWriter(STORE_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (App app : apps.values()) {
                bw.write(Json.obj(
                        "type", REC_APP,
                        "appId", app.appId,
                        "currentVersion", app.currentVersion
                ));
                bw.newLine();

                List<AppVersion> versions = new ArrayList<>(app.versionsByNumber.values());
                versions.sort(Comparator.comparingInt(v -> v.versionNumber));
                for (AppVersion v : versions) {
                    bw.write(Json.obj(
                            "type", REC_VERSION,
                            "appId", app.appId,
                            "versionNumber", v.versionNumber,
                            "createdAt", v.createdAt.toString(),
                            "createdBy", v.createdBy,
                            "active", v.active,
                            "deleted", v.deleted,
                            "accessible", v.accessible,
                            "payload", v.payload
                    ));
                    bw.newLine();
                }
            }

            for (AuditEntry a : audits) {
                bw.write(Json.obj(
                        "type", REC_AUDIT,
                        "timestamp", a.timestamp.toString(),
                        "appId", a.appId,
                        "versionNumber", a.versionNumber,
                        "user", a.user,
                        "status", a.status,
                        "failureReason", a.failureReason
                ));
                bw.newLine();
            }
        }
    }

    // ----------------------------
    // Error response helper
    // ----------------------------

    private static String error(int status, String message, String details) {
        return Json.obj(
                "success", false,
                "status", status,
                "message", message,
                "error", Json.obj(
                        "details", details
                )
        );
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getSimpleName();
        // Avoid printing secrets; keep short.
        if (m.length() > 300) return m.substring(0, 300);
        return m;
    }

    // ----------------------------
    // Auth parsing
    // ----------------------------

    static final class ParsedAuth {
        final boolean ok;
        final String user;
        final Set<String> roles;
        final String errorJson;

        ParsedAuth(boolean ok, String user, Set<String> roles, String errorJson) {
            this.ok = ok;
            this.user = user;
            this.roles = roles;
            this.errorJson = errorJson;
        }
    }

    private static ParsedAuth parseAuth(String[] args) {
        String user = null;
        Set<String> roles = new LinkedHashSet<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--user".equals(a)) {
                if (i + 1 >= args.length) {
                    return new ParsedAuth(false, null, Set.of(), Json.obj("success", false, "status", 400, "message", "--user requires a value"));
                }
                user = args[++i];
            } else if ("--roles".equals(a)) {
                if (i + 1 >= args.length) {
                    return new ParsedAuth(false, null, Set.of(), Json.obj("success", false, "status", 400, "message", "--roles requires a value"));
                }
                String csv = args[++i];
                if (!csv.isBlank()) {
                    for (String r : csv.split(",")) {
                        String rr = r.trim();
                        if (!rr.isEmpty()) roles.add(rr);
                    }
                }
            } else {
                return new ParsedAuth(false, null, Set.of(), Json.obj("success", false, "status", 400, "message", "Unknown auth arg: " + a));
            }
        }

        if (user == null || user.isBlank()) {
            return new ParsedAuth(false, null, Set.of(), Json.obj("success", false, "status", 401, "message", "Unauthenticated - missing --user"));
        }

        // If roles not supplied, treat as authenticated but minimal.
        return new ParsedAuth(true, user, roles, null);
    }

    // ----------------------------
    // Minimal JSON utilities (no deps)
    // ----------------------------

    static final class Json {
        private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

        static String obj(Object... kv) {
            if (kv.length % 2 != 0) throw new IllegalArgumentException("kv must be even");
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            for (int i = 0; i < kv.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(String.valueOf(kv[i]))).append('"').append(':');
                sb.append(val(kv[i + 1]));
            }
            sb.append('}');
            return sb.toString();
        }

        static String arr(List<String> alreadyJsonElements) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < alreadyJsonElements.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(alreadyJsonElements.get(i));
            }
            sb.append(']');
            return sb.toString();
        }

        private static String val(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return '"' + escape(s) + '"';
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            // allow raw JSON strings produced by obj()/arr() to pass through if they look like JSON
            if (v instanceof CharSequence cs) {
                String s = cs.toString();
                String t = s.trim();
                if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) return s;
                return '"' + escape(s) + '"';
            }
            if (v instanceof Instant ins) return '"' + ISO.format(ins) + '"';
            return '"' + escape(String.valueOf(v)) + '"';
        }

        static String escape(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.toString();
        }

        /**
         * Parses a very small subset of JSON: a flat object with string/number/boolean/null values.
         * This is sufficient for reading our own persisted records.
         */
        static Map<String, String> parseFlatObject(String json) {
            String t = json.trim();
            if (!t.startsWith("{") || !t.endsWith("}")) throw new IllegalArgumentException("Not an object");
            t = t.substring(1, t.length() - 1).trim();
            Map<String, String> out = new HashMap<>();
            if (t.isEmpty()) return out;

            int i = 0;
            while (i < t.length()) {
                i = skipWs(t, i);
                if (t.charAt(i) != '"') throw new IllegalArgumentException("Expected key quote");
                int keyEnd = findStringEnd(t, i + 1);
                String key = unescape(t.substring(i + 1, keyEnd));
                i = keyEnd + 1;
                i = skipWs(t, i);
                if (t.charAt(i) != ':') throw new IllegalArgumentException("Expected ':'");
                i++;
                i = skipWs(t, i);

                String value;
                char c = t.charAt(i);
                if (c == '"') {
                    int valEnd = findStringEnd(t, i + 1);
                    value = unescape(t.substring(i + 1, valEnd));
                    i = valEnd + 1;
                } else {
                    int valEnd = i;
                    int braceDepth = 0;
                    int bracketDepth = 0;
                    boolean inStr = false;
                    while (valEnd < t.length()) {
                        char ch = t.charAt(valEnd);
                        if (!inStr) {
                            if (ch == ',' && braceDepth == 0 && bracketDepth == 0) break;
                            if (ch == '{') braceDepth++;
                            else if (ch == '}') braceDepth--;
                            else if (ch == '[') bracketDepth++;
                            else if (ch == ']') bracketDepth--;
                            else if (ch == '"') inStr = true;
                        } else {
                            if (ch == '"' && t.charAt(valEnd - 1) != '\\') inStr = false;
                        }
                        valEnd++;
                    }
                    value = t.substring(i, valEnd).trim();
                    // strip null/true/false/number as raw string
                    if ("null".equals(value)) value = null;
                    i = valEnd;
                }

                out.put(key, value);
                i = skipWs(t, i);
                if (i < t.length()) {
                    if (t.charAt(i) != ',') throw new IllegalArgumentException("Expected ','");
                    i++;
                }
            }

            return out;
        }

        private static int skipWs(String s, int i) {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
            return i;
        }

        private static int findStringEnd(String s, int start) {
            int i = start;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"' && s.charAt(i - 1) != '\\') return i;
                i++;
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i + 1 >= s.length()) throw new IllegalArgumentException("Bad escape");
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 >= s.length()) throw new IllegalArgumentException("Bad unicode escape");
                        String hex = s.substring(i + 1, i + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape: \\" + n);
                }
            }
            return sb.toString();
        }
    }

    // ----------------------------
    // Compliance Audit Log (simple, in-code)
    // ----------------------------
    // The user requested an audit log for compliance. This program records restore operations in
    // the persisted store as AUDIT records (type=AUDIT). This includes:
    // - Application ID
    // - Restored version number
    // - User who initiated the restore
    // - Restore timestamp
    // - Operation status
    // - Failure reason (when applicable)
}
