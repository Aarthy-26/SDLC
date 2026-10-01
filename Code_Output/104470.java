/*
 * Story ID: 104470
 * Title: App Version Management / Version History – Backend
 *
 * What this program does:
 * This is a single-file, runnable Java 17+ program that simulates the backend behavior
 * described in the work item for application version management:
 *   - GET /api/apps/{appId}/versions
 *   - POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * It exposes a tiny HTTP server (built-in JDK HttpServer) with in-memory storage,
 * validates inputs, enforces a simple authorization model, performs transactional restore
 * with a lock per app to avoid concurrent restore conflicts, and returns consistent JSON
 * success/error responses.
 *
 * Compile:
 *   javac 104470.java
 * Run:
 *   java AppVersionManagementBackend --port 8080
 *
 * Assumptions (due to missing specifics in the work item):
 *   - Authorization is simulated via HTTP header "Authorization: Bearer <token>".
 *     Tokens are hardcoded as:
 *       * builder-token  => can read versions
 *       * restorer-token => can read + restore
 *       * admin-token    => can read + restore
 *   - "Application configuration/data" is represented as a JSON string blob stored per version.
 *   - Version metadata "createdBy" is a username in the token map.
 *   - Response formats are defined here because the work item does not specify exact schemas.
 *   - "Invalid/deleted/inaccessible versions" are represented by flags (deleted=false, accessible=true).
 *
 * Requires: Java 17+
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

class AppVersionManagementBackend {

    // ----------------------------
    // Data model (in-memory)
    // ----------------------------

    static final class App {
        final String appId;
        final Map<Integer, Version> versionsByNumber = new HashMap<>();
        int activeVersion;
        String activeConfig;

        App(String appId) {
            this.appId = appId;
        }
    }

    static final class Version {
        final int versionNumber;
        final Instant createdAt;
        final String createdBy;
        final String configSnapshot;
        boolean deleted;
        boolean accessible;

        Version(int versionNumber, Instant createdAt, String createdBy, String configSnapshot,
                boolean deleted, boolean accessible) {
            this.versionNumber = versionNumber;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.configSnapshot = configSnapshot;
            this.deleted = deleted;
            this.accessible = accessible;
        }
    }

    static final class UserContext {
        final String token;
        final String username;
        final Set<String> permissions;

        UserContext(String token, String username, Set<String> permissions) {
            this.token = token;
            this.username = username;
            this.permissions = permissions;
        }

        boolean canRead(String appId) {
            // For this simulation: any authenticated user with READ_VERSIONS can read any app.
            return permissions.contains("READ_VERSIONS");
        }

        boolean canRestore(String appId) {
            // For this simulation: permission RESTORE_VERSIONS is required.
            return permissions.contains("RESTORE_VERSIONS");
        }
    }

    static final class AuditEvent {
        final Instant timestamp;
        final String appId;
        final int versionNumber;
        final String username;
        final String status; // SUCCESS / FAILURE
        final String failureReason;

        AuditEvent(Instant timestamp, String appId, int versionNumber, String username, String status, String failureReason) {
            this.timestamp = timestamp;
            this.appId = appId;
            this.versionNumber = versionNumber;
            this.username = username;
            this.status = status;
            this.failureReason = failureReason;
        }
    }

    // ----------------------------
    // Storage and concurrency
    // ----------------------------

    private final Map<String, App> apps = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> appLocks = new ConcurrentHashMap<>();
    private final List<AuditEvent> auditLog = Collections.synchronizedList(new ArrayList<>());

    // Hardcoded "auth" token -> user
    private final Map<String, UserContext> tokenToUser = Map.of(
            "builder-token", new UserContext("builder-token", "builder", Set.of("READ_VERSIONS")),
            "restorer-token", new UserContext("restorer-token", "restorer", Set.of("READ_VERSIONS", "RESTORE_VERSIONS")),
            "admin-token", new UserContext("admin-token", "admin", Set.of("READ_VERSIONS", "RESTORE_VERSIONS"))
    );

    // ----------------------------
    // Main
    // ----------------------------

    public static void main(String[] args) throws Exception {
        int port = 8080;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        AppVersionManagementBackend backend = new AppVersionManagementBackend();
        backend.seedSampleData();
        backend.startServer(port);

        System.out.println("Server started on http://localhost:" + port);
        System.out.println("Endpoints:");
        System.out.println("  GET  /api/apps/{appId}/versions");
        System.out.println("  POST /api/apps/{appId}/versions/{versionNumber}/restore");
        System.out.println("Auth: Authorization: Bearer builder-token | restorer-token | admin-token");
    }

    private void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/apps", new AppsHandler(this));
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
    }

    private void seedSampleData() {
        // App A with versions 1..3
        App a = new App("appA");
        a.versionsByNumber.put(1, new Version(1, Instant.parse("2025-01-10T10:15:30Z"), "builder", "{\"flow\":\"v1\"}", false, true));
        a.versionsByNumber.put(2, new Version(2, Instant.parse("2025-02-05T09:05:00Z"), "builder", "{\"flow\":\"v2\"}", false, true));
        a.versionsByNumber.put(3, new Version(3, Instant.parse("2025-03-12T18:22:10Z"), "restorer", "{\"flow\":\"v3\"}", false, true));
        a.activeVersion = 3;
        a.activeConfig = a.versionsByNumber.get(3).configSnapshot;

        // App B with one inaccessible version and one valid
        App b = new App("appB");
        b.versionsByNumber.put(1, new Version(1, Instant.parse("2025-01-01T00:00:00Z"), "builder", "{\"cfg\":\"b1\"}", false, false));
        b.versionsByNumber.put(2, new Version(2, Instant.parse("2025-04-01T00:00:00Z"), "admin", "{\"cfg\":\"b2\"}", false, true));
        b.activeVersion = 2;
        b.activeConfig = b.versionsByNumber.get(2).configSnapshot;

        apps.put(a.appId, a);
        apps.put(b.appId, b);
    }

    // ----------------------------
    // HTTP Handler
    // ----------------------------

    static final class AppsHandler implements HttpHandler {
        private final AppVersionManagementBackend backend;

        AppsHandler(AppVersionManagementBackend backend) {
            this.backend = backend;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
                URI uri = ex.getRequestURI();
                String path = uri.getPath();

                // Expected patterns:
                // /api/apps/{appId}/versions
                // /api/apps/{appId}/versions/{versionNumber}/restore

                List<String> parts = splitPath(path);
                // parts for "/api/apps/..." => [api, apps, ...]
                if (parts.size() < 4 || !"api".equals(parts.get(0)) || !"apps".equals(parts.get(1))) {
                    sendJson(ex, 404, error("NOT_FOUND", "Unknown endpoint", Map.of("path", path)));
                    return;
                }

                String appId = parts.get(2);
                String segment = parts.get(3);
                if (!"versions".equals(segment)) {
                    sendJson(ex, 404, error("NOT_FOUND", "Unknown endpoint", Map.of("path", path)));
                    return;
                }

                if ("GET".equals(method) && parts.size() == 4) {
                    backend.handleGetVersions(ex, appId);
                    return;
                }

                if ("POST".equals(method) && parts.size() == 6 && "restore".equals(parts.get(5))) {
                    String versionStr = parts.get(4);
                    backend.handleRestore(ex, appId, versionStr);
                    return;
                }

                sendJson(ex, 405, error("METHOD_NOT_ALLOWED", "Method/path not supported", Map.of("method", method, "path", path)));
            } catch (Exception e) {
                sendJson(ex, 500, error("INTERNAL_ERROR", "Unexpected server error", Map.of("exception", e.getClass().getName(), "message", safeMsg(e))));
            } finally {
                ex.close();
            }
        }

        private static List<String> splitPath(String path) {
            String[] raw = path.split("/");
            List<String> parts = new ArrayList<>();
            for (String r : raw) {
                if (!r.isEmpty()) parts.add(r);
            }
            return parts;
        }

        private static String safeMsg(Throwable t) {
            String m = t.getMessage();
            return m == null ? "" : m;
        }
    }

    // ----------------------------
    // Endpoint Implementations
    // ----------------------------

    private void handleGetVersions(HttpExchange ex, String appId) throws IOException {
        Optional<UserContext> userOpt = authenticate(ex);
        if (userOpt.isEmpty()) {
            sendJson(ex, 401, error("UNAUTHORIZED", "Missing or invalid Authorization header", Map.of()));
            return;
        }
        UserContext user = userOpt.get();
        if (!user.canRead(appId)) {
            sendJson(ex, 403, error("FORBIDDEN", "User not authorized to view versions", Map.of("appId", appId)));
            return;
        }

        App app = apps.get(appId);
        if (app == null) {
            sendJson(ex, 404, error("APP_NOT_FOUND", "Application does not exist", Map.of("appId", appId)));
            return;
        }

        List<Version> versions = new ArrayList<>();
        for (Version v : app.versionsByNumber.values()) {
            if (v.deleted) continue;
            if (!v.accessible) continue;
            versions.add(v);
        }
        versions.sort(Comparator.comparingInt((Version v) -> v.versionNumber).reversed());

        if (versions.isEmpty()) {
            // Requirement: "Return an appropriate response when no versions are available."
            // Assumption: return 200 with empty list and a message.
            sendJson(ex, 200, ok(Map.of(
                    "appId", appId,
                    "versions", List.of(),
                    "message", "No versions available"
            )));
            return;
        }

        List<Object> out = new ArrayList<>();
        for (Version v : versions) {
            out.add(Map.of(
                    "versionNumber", v.versionNumber,
                    "createdAt", v.createdAt.toString(),
                    "createdBy", v.createdBy,
                    "isActive", v.versionNumber == app.activeVersion
            ));
        }

        sendJson(ex, 200, ok(Map.of(
                "appId", appId,
                "activeVersion", app.activeVersion,
                "versions", out
        )));
    }

    private void handleRestore(HttpExchange ex, String appId, String versionStr) throws IOException {
        Optional<UserContext> userOpt = authenticate(ex);
        if (userOpt.isEmpty()) {
            sendJson(ex, 401, error("UNAUTHORIZED", "Missing or invalid Authorization header", Map.of()));
            return;
        }
        UserContext user = userOpt.get();

        App app = apps.get(appId);
        if (app == null) {
            audit(appId, -1, user.username, "FAILURE", "APP_NOT_FOUND");
            sendJson(ex, 404, error("APP_NOT_FOUND", "Application does not exist", Map.of("appId", appId)));
            return;
        }

        if (!user.canRestore(appId)) {
            audit(appId, parseIntOrNeg(versionStr), user.username, "FAILURE", "FORBIDDEN");
            sendJson(ex, 403, error("FORBIDDEN", "User not authorized to restore versions", Map.of("appId", appId)));
            return;
        }

        int versionNumber;
        try {
            versionNumber = Integer.parseInt(versionStr);
        } catch (NumberFormatException nfe) {
            audit(appId, -1, user.username, "FAILURE", "INVALID_VERSION_NUMBER");
            sendJson(ex, 400, error("BAD_REQUEST", "versionNumber must be an integer", Map.of("versionNumber", versionStr)));
            return;
        }

        Version target = app.versionsByNumber.get(versionNumber);
        if (target == null) {
            audit(appId, versionNumber, user.username, "FAILURE", "VERSION_NOT_FOUND");
            sendJson(ex, 404, error("VERSION_NOT_FOUND", "Version does not exist for application", Map.of("appId", appId, "versionNumber", versionNumber)));
            return;
        }

        if (target.deleted || !target.accessible) {
            audit(appId, versionNumber, user.username, "FAILURE", "VERSION_INACCESSIBLE");
            sendJson(ex, 409, error("VERSION_INACCESSIBLE", "Cannot restore an invalid or inaccessible version", Map.of("appId", appId, "versionNumber", versionNumber)));
            return;
        }

        // Concurrency: prevent concurrent restore operations safely.
        ReentrantLock lock = appLocks.computeIfAbsent(appId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            audit(appId, versionNumber, user.username, "FAILURE", "RESTORE_CONFLICT");
            sendJson(ex, 409, error("RESTORE_CONFLICT", "Another restore operation is in progress", Map.of("appId", appId)));
            return;
        }

        try {
            // Transactional-ish restore: stage changes then commit.
            int oldActiveVersion = app.activeVersion;
            String oldActiveConfig = app.activeConfig;

            try {
                // Simulate potential failure if header provided (useful for QA).
                // Not required by story; kept minimal. If not present, behaves normally.
                String failHeader = header(ex.getRequestHeaders(), "X-Simulate-Failure").orElse("");
                if ("true".equalsIgnoreCase(failHeader)) {
                    throw new RuntimeException("Simulated restore failure");
                }

                // Restore all required components/configurations consistently.
                // Here represented by configSnapshot.
                String newConfig = target.configSnapshot;

                // Commit
                app.activeConfig = newConfig;
                app.activeVersion = versionNumber;

                audit(appId, versionNumber, user.username, "SUCCESS", null);
                sendJson(ex, 200, ok(Map.of(
                        "appId", appId,
                        "restoredVersion", versionNumber,
                        "activeVersion", app.activeVersion,
                        "message", "Restore completed"
                )));
            } catch (Exception restoreFailure) {
                // Rollback to ensure not partially restored.
                app.activeVersion = oldActiveVersion;
                app.activeConfig = oldActiveConfig;

                audit(appId, versionNumber, user.username, "FAILURE", restoreFailure.getMessage());
                sendJson(ex, 500, error("RESTORE_FAILED", "Unexpected restoration failure", Map.of(
                        "appId", appId,
                        "versionNumber", versionNumber,
                        "details", safeMsg(restoreFailure)
                )));
            }
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------
    // Auth + helpers
    // ----------------------------

    private Optional<UserContext> authenticate(HttpExchange ex) {
        Optional<String> auth = header(ex.getRequestHeaders(), "Authorization");
        if (auth.isEmpty()) return Optional.empty();
        String v = auth.get().trim();
        if (!v.toLowerCase(Locale.ROOT).startsWith("bearer ")) return Optional.empty();
        String token = v.substring("bearer ".length()).trim();
        return Optional.ofNullable(tokenToUser.get(token));
    }

    private static Optional<String> header(Headers headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> vals = e.getValue();
                if (vals != null && !vals.isEmpty()) {
                    return Optional.ofNullable(vals.get(0));
                }
            }
        }
        return Optional.empty();
    }

    private void audit(String appId, int versionNumber, String username, String status, String failureReason) {
        auditLog.add(new AuditEvent(Instant.now(), appId, versionNumber, username, status, failureReason));
        // Minimal logging to stdout as per "Audit & Logging" requirement.
        System.out.println("AUDIT {" +
                "ts=\"" + Instant.now() + "\"," +
                " appId=\"" + appId + "\"," +
                " version=" + versionNumber + "," +
                " user=\"" + username + "\"," +
                " status=\"" + status + "\"," +
                " failureReason=\"" + (failureReason == null ? "" : failureReason.replace("\"", "'")) + "\"" +
                " }");
    }

    private static int parseIntOrNeg(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    // ----------------------------
    // JSON response helpers
    // ----------------------------

    private static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "success");
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> error(String code, String message, Map<String, Object> details) {
        Map<String, Object> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", message);
        err.put("error", Map.of(
                "code", code,
                "details", details == null ? Map.of() : details
        ));
        return err;
    }

    private static void sendJson(HttpExchange ex, int statusCode, Map<String, Object> body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Minimal JSON serializer for Maps/Lists/Strings/Numbers/Booleans/null.
    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(k));
                sb.append(':');
                sb.append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object v : list) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(v));
            }
            sb.append(']');
            return sb.toString();
        }
        // Fallback to string
        return toJson(String.valueOf(o));
    }

    private static String escapeJson(String s) {
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
