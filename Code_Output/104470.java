/*
 * Work Item: 104470 — App Version Management / Version History – Backend
 * Type/State: (from work item) Ready for QA
 *
 * What this program does
 * ---------------------
 * Generates a minimal, runnable Java program that simulates the backend behavior required by the story:
 *  - GET  /api/apps/{appId}/versions
 *  - POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * Since this deliverable must be a single-file Java program with no external setup (no Spring, no DB),
 * it implements an in-memory HTTP server (com.sun.net.httpserver.HttpServer) with:
 *  - Application existence validation
 *  - Version listing (latest-first), with metadata: versionNumber, createdAt, createdBy, isActive
 *  - Restore operation with authorization checks, app/version relationship checks, and transactional semantics
 *  - Consistent JSON success/error response structure
 *  - Basic audit log (in-memory) for restore operations
 *  - Safe concurrent restore handling per-app via lock
 *
 * Compile:
 *   javac 104470.java
 * Run:
 *   java AppVersionHistoryBackend --port 8080
 *
 * Assumptions (due to lack of specific DB/domain details in the work item):
 *  1) Versions are identified by integer versionNumber.
 *  2) Authorization is simulated via HTTP header: X-User and X-Roles (comma-separated, e.g. EDITOR,ADMIN).
 *     - Listing versions requires the user to be authenticated and have access to the app.
 *     - Restore requires role ADMIN or EDITOR.
 *  3) "Invalid/deleted/inaccessible versions" are simulated by a boolean flag per version.
 *  4) "Transactional restore" is simulated by cloning app state before applying restore; on failure the
 *     original state remains unchanged.
 *  5) If no versions exist for an app, GET returns 200 with empty list and a clear message.
 *
 * Requires Java 17+.
 */

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

class AppVersionHistoryBackend {

    // ----------------------------
    // Data model (in-memory)
    // ----------------------------

    static final class App {
        final String appId;
        String name;
        int activeVersion;
        Map<String, Object> activeConfig; // represents app configuration/data

        final Map<Integer, AppVersion> versions = new HashMap<>();
        final ReentrantLock restoreLock = new ReentrantLock();

        App(String appId, String name) {
            this.appId = appId;
            this.name = name;
            this.activeVersion = 1;
            this.activeConfig = new LinkedHashMap<>();
        }
    }

    static final class AppVersion {
        final int versionNumber;
        final OffsetDateTime createdAt;
        final String createdBy;
        boolean active;
        boolean accessible;
        Map<String, Object> configSnapshot;

        AppVersion(int versionNumber, OffsetDateTime createdAt, String createdBy,
                   boolean active, boolean accessible, Map<String, Object> configSnapshot) {
            this.versionNumber = versionNumber;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.active = active;
            this.accessible = accessible;
            this.configSnapshot = configSnapshot;
        }
    }

    static final class AuditEntry {
        final UUID id = UUID.randomUUID();
        final String appId;
        final int restoredVersion;
        final String username;
        final Instant timestamp;
        final String status;
        final String failureReason;

        AuditEntry(String appId, int restoredVersion, String username, Instant timestamp, String status, String failureReason) {
            this.appId = appId;
            this.restoredVersion = restoredVersion;
            this.username = username;
            this.timestamp = timestamp;
            this.status = status;
            this.failureReason = failureReason;
        }
    }

    // ----------------------------
    // Repository / storage
    // ----------------------------

    private static final Map<String, App> APPS = new ConcurrentHashMap<>();
    private static final List<AuditEntry> AUDIT_LOG = Collections.synchronizedList(new ArrayList<>());

    // Simple access model: appId -> usernames that can access.
    private static final Map<String, Set<String>> APP_ACCESS = new ConcurrentHashMap<>();

    // Roles allowed to restore
    private static final Set<String> RESTORE_ROLES = Set.of("ADMIN", "EDITOR");

    // ----------------------------
    // Entry point
    // ----------------------------

    public static void main(String[] args) throws Exception {
        int port = 8080;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        seedDemoData();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/apps", new AppsHandler());
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));

        System.out.println("AppVersionHistoryBackend listening on http://localhost:" + port);
        System.out.println("Endpoints:");
        System.out.println("  GET  /api/apps/{appId}/versions");
        System.out.println("  POST /api/apps/{appId}/versions/{versionNumber}/restore");
        System.out.println("Auth headers (simulated): X-User and X-Roles (comma-separated)");

        server.start();
    }

    private static void seedDemoData() {
        App app = new App("app-123", "DemoApp");
        app.activeConfig.put("theme", "light");
        app.activeConfig.put("widgets", List.of("chart", "table"));

        OffsetDateTime t1 = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        OffsetDateTime t2 = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime t3 = OffsetDateTime.now(ZoneOffset.UTC);

        app.versions.put(1, new AppVersion(1, t1, "alice", true, true, deepCopyMap(app.activeConfig)));
        // Simulate changes for v2
        Map<String, Object> cfg2 = deepCopyMap(app.activeConfig);
        cfg2.put("theme", "dark");
        app.versions.put(2, new AppVersion(2, t2, "bob", false, true, deepCopyMap(cfg2)));
        // v3 inaccessible
        Map<String, Object> cfg3 = deepCopyMap(cfg2);
        cfg3.put("widgets", List.of("chart"));
        app.versions.put(3, new AppVersion(3, t3, "carol", false, false, deepCopyMap(cfg3)));

        app.activeVersion = 1;
        markActive(app, 1);

        APPS.put(app.appId, app);

        APP_ACCESS.put(app.appId, new HashSet<>(Set.of("alice", "bob", "carol", "admin")));

        // Another app with no versions
        App empty = new App("app-empty", "EmptyApp");
        empty.activeVersion = 0;
        empty.activeConfig = new LinkedHashMap<>();
        APPS.put(empty.appId, empty);
        APP_ACCESS.put(empty.appId, new HashSet<>(Set.of("admin")));
    }

    // ----------------------------
    // HTTP routing handler
    // ----------------------------

    static final class AppsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                URI uri = exchange.getRequestURI();
                String path = uri.getPath();
                String method = exchange.getRequestMethod();

                // Expected patterns:
                // /api/apps/{appId}/versions
                // /api/apps/{appId}/versions/{versionNumber}/restore
                String[] parts = path.split("/");
                // parts: ["", "api", "apps", "{appId}", "versions", ...]
                if (parts.length < 5 || !"api".equals(parts[1]) || !"apps".equals(parts[2])) {
                    writeJson(exchange, 404, error("NotFound", "Unknown endpoint", Map.of("path", path)));
                    return;
                }
                String appId = parts[3];
                String segment = parts[4];
                if (!"versions".equals(segment)) {
                    writeJson(exchange, 404, error("NotFound", "Unknown endpoint", Map.of("path", path)));
                    return;
                }

                if (parts.length == 5) {
                    if (!"GET".equalsIgnoreCase(method)) {
                        writeJson(exchange, 405, error("MethodNotAllowed", "Only GET is supported for this endpoint", Map.of("method", method)));
                        return;
                    }
                    handleGetVersions(exchange, appId);
                    return;
                }

                if (parts.length == 7 && "restore".equals(parts[6])) {
                    if (!"POST".equalsIgnoreCase(method)) {
                        writeJson(exchange, 405, error("MethodNotAllowed", "Only POST is supported for this endpoint", Map.of("method", method)));
                        return;
                    }
                    int versionNumber;
                    try {
                        versionNumber = Integer.parseInt(parts[5]);
                    } catch (NumberFormatException nfe) {
                        writeJson(exchange, 400, error("BadRequest", "versionNumber must be an integer", Map.of("versionNumber", parts[5])));
                        return;
                    }
                    handleRestore(exchange, appId, versionNumber);
                    return;
                }

                writeJson(exchange, 404, error("NotFound", "Unknown endpoint", Map.of("path", path)));

            } catch (Exception e) {
                writeJson(exchange, 500, error("ServerError", "Unexpected server error", Map.of("exception", e.getClass().getSimpleName(), "message", safeMsg(e))));
            }
        }

        private void handleGetVersions(HttpExchange exchange, String appId) throws IOException {
            UserContext user = authenticate(exchange);
            if (user == null) return; // response already written

            App app = APPS.get(appId);
            if (app == null) {
                writeJson(exchange, 404, error("NotFound", "Application not found", Map.of("appId", appId)));
                return;
            }

            if (!isAuthorizedForApp(user, appId)) {
                writeJson(exchange, 403, error("Forbidden", "User is not authorized to access this application", Map.of("appId", appId, "user", user.username)));
                return;
            }

            List<AppVersion> list = new ArrayList<>(app.versions.values());
            // Exclude invalid/deleted/inaccessible versions
            list.removeIf(v -> !v.accessible);
            list.sort(Comparator.comparingInt((AppVersion v) -> v.versionNumber).reversed());

            List<Map<String, Object>> payload = new ArrayList<>();
            for (AppVersion v : list) {
                payload.add(Map.of(
                        "versionNumber", v.versionNumber,
                        "createdAt", v.createdAt.toString(),
                        "createdBy", v.createdBy,
                        "isActive", v.active
                ));
            }

            String message = payload.isEmpty() ? "No versions available for application" : "Versions retrieved";
            writeJson(exchange, 200, success(message, Map.of(
                    "appId", appId,
                    "versions", payload
            )));
        }

        private void handleRestore(HttpExchange exchange, String appId, int versionNumber) throws IOException {
            UserContext user = authenticate(exchange);
            if (user == null) return;

            App app = APPS.get(appId);
            if (app == null) {
                writeJson(exchange, 404, error("NotFound", "Application not found", Map.of("appId", appId)));
                return;
            }

            if (!isAuthorizedForApp(user, appId)) {
                writeJson(exchange, 403, error("Forbidden", "User is not authorized to access this application", Map.of("appId", appId, "user", user.username)));
                return;
            }

            if (!canRestore(user)) {
                writeJson(exchange, 403, error("Forbidden", "User is not authorized to restore application versions", Map.of("requiredRoles", RESTORE_ROLES, "userRoles", user.roles)));
                return;
            }

            AppVersion target = app.versions.get(versionNumber);
            if (target == null) {
                writeJson(exchange, 404, error("NotFound", "Version not found for application", Map.of("appId", appId, "versionNumber", versionNumber)));
                audit("FAILED", appId, versionNumber, user.username, "Version does not exist");
                return;
            }

            if (!target.accessible) {
                writeJson(exchange, 409, error("Conflict", "Requested version is invalid or inaccessible", Map.of("appId", appId, "versionNumber", versionNumber)));
                audit("FAILED", appId, versionNumber, user.username, "Version inaccessible");
                return;
            }

            boolean locked = app.restoreLock.tryLock();
            if (!locked) {
                writeJson(exchange, 409, error("Conflict", "Restore operation conflict: another restore is in progress", Map.of("appId", appId)));
                audit("FAILED", appId, versionNumber, user.username, "Concurrent restore conflict");
                return;
            }

            try {
                // Transactional semantics: copy current state; if anything fails, revert.
                int prevActiveVersion = app.activeVersion;
                Map<String, Object> prevConfig = deepCopyMap(app.activeConfig);
                Map<Integer, AppVersion> prevVersions = deepCopyVersions(app.versions);

                try {
                    // Optional failure simulation: if request body contains {"simulateFailure": true}
                    boolean simulateFailure = parseSimulateFailure(exchange);

                    // Restore app configuration/data from snapshot
                    app.activeConfig = deepCopyMap(target.configSnapshot);

                    // Update active version flags
                    app.activeVersion = versionNumber;
                    markActive(app, versionNumber);

                    // Preserve required audit/version info: (in this simplified model, we keep all versions unchanged)

                    if (simulateFailure) {
                        throw new RuntimeException("Simulated restore failure");
                    }

                    audit("SUCCESS", appId, versionNumber, user.username, null);
                    writeJson(exchange, 200, success("Application restored successfully", Map.of(
                            "appId", appId,
                            "restoredVersion", versionNumber,
                            "activeVersion", app.activeVersion
                    )));

                } catch (Exception restoreFailure) {
                    // rollback
                    app.activeVersion = prevActiveVersion;
                    app.activeConfig = prevConfig;
                    app.versions.clear();
                    app.versions.putAll(prevVersions);

                    audit("FAILED", appId, versionNumber, user.username, safeMsg(restoreFailure));
                    writeJson(exchange, 500, error("RestoreFailed", "Unexpected restoration failure", Map.of(
                            "appId", appId,
                            "versionNumber", versionNumber,
                            "reason", safeMsg(restoreFailure)
                    )));
                }

            } finally {
                app.restoreLock.unlock();
            }
        }

        private boolean parseSimulateFailure(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            if (body == null || body.isBlank()) return false;
            // Minimal parsing to avoid dependencies: checks for "simulateFailure": true
            String normalized = body.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            return normalized.contains("\"simulatefailure\":true");
        }
    }

    // ----------------------------
    // Auth & authorization
    // ----------------------------

    static final class UserContext {
        final String username;
        final Set<String> roles;

        UserContext(String username, Set<String> roles) {
            this.username = username;
            this.roles = roles;
        }
    }

    private static UserContext authenticate(HttpExchange exchange) throws IOException {
        Headers h = exchange.getRequestHeaders();
        String user = firstHeader(h, "X-User");
        if (user == null || user.isBlank()) {
            writeJson(exchange, 401, error("Unauthorized", "Missing X-User header", Map.of("header", "X-User")));
            return null;
        }

        String rolesHeader = firstHeader(h, "X-Roles");
        Set<String> roles = new HashSet<>();
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            for (String r : rolesHeader.split(",")) {
                String rr = r.trim();
                if (!rr.isEmpty()) roles.add(rr.toUpperCase(Locale.ROOT));
            }
        }
        return new UserContext(user.trim(), roles);
    }

    private static boolean isAuthorizedForApp(UserContext user, String appId) {
        Set<String> allowed = APP_ACCESS.get(appId);
        if (allowed == null) return false;
        return allowed.contains(user.username);
    }

    private static boolean canRestore(UserContext user) {
        for (String r : user.roles) {
            if (RESTORE_ROLES.contains(r)) return true;
        }
        return false;
    }

    // ----------------------------
    // Restore helpers
    // ----------------------------

    private static void markActive(App app, int versionNumber) {
        for (AppVersion v : app.versions.values()) {
            v.active = (v.versionNumber == versionNumber);
        }
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        if (src == null) return new LinkedHashMap<>();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                // unchecked but safe for our usage
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                copy.put(e.getKey(), deepCopyMap(mm));
            } else if (v instanceof List<?> l) {
                copy.put(e.getKey(), new ArrayList<>(l));
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }

    private static Map<Integer, AppVersion> deepCopyVersions(Map<Integer, AppVersion> src) {
        Map<Integer, AppVersion> copy = new HashMap<>();
        for (Map.Entry<Integer, AppVersion> e : src.entrySet()) {
            AppVersion v = e.getValue();
            copy.put(e.getKey(), new AppVersion(
                    v.versionNumber,
                    v.createdAt,
                    v.createdBy,
                    v.active,
                    v.accessible,
                    deepCopyMap(v.configSnapshot)
            ));
        }
        return copy;
    }

    // ----------------------------
    // Audit
    // ----------------------------

    private static void audit(String status, String appId, int version, String username, String failureReason) {
        AUDIT_LOG.add(new AuditEntry(appId, version, username, Instant.now(), status, failureReason));
    }

    // ----------------------------
    // JSON response helpers (no external deps)
    // ----------------------------

    private static Map<String, Object> success(String message, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", message);
        m.put("data", data);
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    private static Map<String, Object> error(String errorType, String message, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", errorType);
        m.put("message", message);
        m.put("details", details);
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        if (is == null) return "";
        byte[] buf = is.readAllBytes();
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static String firstHeader(Headers headers, String name) {
        List<String> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        return v.get(0);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        // avoid returning huge/stack info
        m = m.replaceAll("[\\r\\n\\t]+", " ").trim();
        return m.length() > 400 ? m.substring(0, 400) : m;
    }

    // Minimal JSON serializer for primitives, maps, lists.
    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, o);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                String k = Objects.toString(e.getKey());
                sb.append('"').append(escapeJson(k)).append('"').append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else if (o instanceof Set<?> set) {
            // JSON doesn't have set; serialize as array
            sb.append('[');
            boolean first = true;
            for (Object item : set) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else {
            // fallback to string
            sb.append('"').append(escapeJson(o.toString())).append('"');
        }
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
