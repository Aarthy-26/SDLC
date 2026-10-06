/*
 * Work Item: 104470
 * Title: App Version Management / Version History – Backend
 *
 * What this program does:
 * - Implements a minimal, runnable HTTP backend (no external dependencies) that exposes:
 *     GET  /api/apps/{appId}/versions
 *     POST /api/apps/{appId}/versions/{versionNumber}/restore
 * - Uses an in-memory store to model applications, versions, authorization, transactional restore,
 *   concurrency control, and audit logging as required by the work item.
 *
 * Compile:
 *   javac 104470.java
 *
 * Run:
 *   java AppVersionManagementServer --port=8080
 *
 * Assumptions (due to missing DB / existing app model in provided repo snapshot):
 * - "Application" and "Version" persistence is simulated in-memory.
 * - Authorization is simulated via HTTP headers:
 *     X-User: <username>
 *     X-Roles: comma-separated roles (e.g. APP_VIEWER,APP_RESTORER,ADMIN)
 *   Required permission:
 *     - GET versions: authenticated user with APP_VIEWER or ADMIN
 *     - RESTORE: authenticated user with APP_RESTORER or ADMIN
 * - Version numbers are positive integers.
 * - Invalid/deleted/inaccessible versions are modeled by a boolean flag and are excluded.
 *
 * Requires Java 17+.
 */

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.concurrent.ReentrantReadWriteLock;

class AppVersionManagementServer {

    // --------------------------- API Response Model ---------------------------

    static final class ApiResponse {
        final boolean success;
        final String message;
        final Object data;
        final Instant timestamp;
        final ErrorDetails error;

        ApiResponse(boolean success, String message, Object data, ErrorDetails error) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.error = error;
            this.timestamp = Instant.now();
        }

        static ApiResponse ok(String message, Object data) {
            return new ApiResponse(true, message, data, null);
        }

        static ApiResponse ok(String message) {
            return new ApiResponse(true, message, null, null);
        }

        static ApiResponse fail(String message, ErrorDetails error) {
            return new ApiResponse(false, message, null, error);
        }
    }

    static final class ErrorDetails {
        final int status;
        final String code;
        final String details;

        ErrorDetails(int status, String code, String details) {
            this.status = status;
            this.code = code;
            this.details = details;
        }
    }

    // --------------------------- Domain Model ---------------------------

    static final class App {
        final String appId;
        String activeConfigJson;
        int activeVersion;

        // Concurrency control: ensure restore is transactional and safe.
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

        App(String appId, String activeConfigJson, int activeVersion) {
            this.appId = appId;
            this.activeConfigJson = activeConfigJson;
            this.activeVersion = activeVersion;
        }
    }

    static final class AppVersion {
        final String appId;
        final int versionNumber;
        final OffsetDateTime createdAt;
        final String createdBy;
        final String configJson;

        // Business rules flags
        boolean deleted;
        boolean accessible;

        AppVersion(String appId, int versionNumber, OffsetDateTime createdAt, String createdBy, String configJson,
                   boolean deleted, boolean accessible) {
            this.appId = appId;
            this.versionNumber = versionNumber;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.configJson = configJson;
            this.deleted = deleted;
            this.accessible = accessible;
        }
    }

    // --------------------------- Audit Log Model ---------------------------

    static final class AuditEntry {
        final String auditId;
        final String appId;
        final int restoredVersion;
        final String user;
        final OffsetDateTime timestamp;
        final String status;
        final String failureReason;

        AuditEntry(String appId, int restoredVersion, String user, String status, String failureReason) {
            this.auditId = UUID.randomUUID().toString();
            this.appId = appId;
            this.restoredVersion = restoredVersion;
            this.user = user;
            this.timestamp = OffsetDateTime.now(ZoneOffset.UTC);
            this.status = status;
            this.failureReason = failureReason;
        }
    }

    // --------------------------- In-memory Store ---------------------------

    static final class Store {
        final Map<String, App> apps = new ConcurrentHashMap<>();
        // appId -> versions
        final Map<String, Map<Integer, AppVersion>> versions = new ConcurrentHashMap<>();
        final List<AuditEntry> audit = java.util.Collections.synchronizedList(new ArrayList<>());

        Optional<App> getApp(String appId) {
            return Optional.ofNullable(apps.get(appId));
        }

        Optional<AppVersion> getVersion(String appId, int version) {
            Map<Integer, AppVersion> map = versions.get(appId);
            if (map == null) return Optional.empty();
            return Optional.ofNullable(map.get(version));
        }

        List<AppVersion> listValidVersions(String appId) {
            Map<Integer, AppVersion> map = versions.get(appId);
            if (map == null) return List.of();
            List<AppVersion> out = new ArrayList<>();
            for (AppVersion v : map.values()) {
                if (!v.deleted && v.accessible) out.add(v);
            }
            out.sort(Comparator.comparingInt((AppVersion v) -> v.versionNumber).reversed());
            return out;
        }

        void addApp(App app) {
            apps.put(app.appId, app);
            versions.computeIfAbsent(app.appId, k -> new ConcurrentHashMap<>());
        }

        void addVersion(AppVersion v) {
            versions.computeIfAbsent(v.appId, k -> new ConcurrentHashMap<>()).put(v.versionNumber, v);
        }

        void addAudit(AuditEntry e) {
            audit.add(e);
        }

        List<AuditEntry> listAudit() {
            synchronized (audit) {
                return new ArrayList<>(audit);
            }
        }
    }

    // --------------------------- Auth Model ---------------------------

    static final class UserContext {
        final String username;
        final Set<String> roles;

        UserContext(String username, Set<String> roles) {
            this.username = username;
            this.roles = roles;
        }

        boolean isAuthenticated() {
            return username != null && !username.isBlank();
        }

        boolean hasAnyRole(String... accepted) {
            for (String a : accepted) {
                if (roles.contains(a)) return true;
            }
            return false;
        }
    }

    // --------------------------- Server ---------------------------

    private final Store store;

    AppVersionManagementServer(Store store) {
        this.store = store;
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = Integer.parseInt(a.substring("--port=".length()));
            }
        }

        Store store = new Store();
        seed(store);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/apps", new AppsHandler(store));
        server.createContext("/api/audit", new AuditHandler(store));
        server.createContext("/", new RootHandler());
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();

        System.out.println("AppVersionManagementServer started on http://localhost:" + port);
        System.out.println("Endpoints:");
        System.out.println("  GET  /api/apps/{appId}/versions");
        System.out.println("  POST /api/apps/{appId}/versions/{versionNumber}/restore");
        System.out.println("  GET  /api/audit (demo: view restore audit entries)");
        System.out.println();
        System.out.println("Auth via headers (simulated):");
        System.out.println("  X-User: <username>");
        System.out.println("  X-Roles: APP_VIEWER,APP_RESTORER (or ADMIN)");
    }

    private static void seed(Store s) {
        // Demo data: one app with versions.
        App app = new App("app-123", "{\"name\":\"Demo App\",\"widgets\":[\"A\"]}", 3);
        s.addApp(app);

        s.addVersion(new AppVersion("app-123", 1, OffsetDateTime.now(ZoneOffset.UTC).minusDays(10), "alice",
                "{\"name\":\"Demo App\",\"widgets\":[\"A\"]}", false, true));
        s.addVersion(new AppVersion("app-123", 2, OffsetDateTime.now(ZoneOffset.UTC).minusDays(5), "bob",
                "{\"name\":\"Demo App\",\"widgets\":[\"A\",\"B\"]}", false, true));
        s.addVersion(new AppVersion("app-123", 3, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), "carol",
                "{\"name\":\"Demo App\",\"widgets\":[\"A\",\"B\",\"C\"]}", false, true));

        // A deleted/inaccessible version to demonstrate exclusion
        s.addVersion(new AppVersion("app-123", 99, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2), "mallory",
                "{\"name\":\"Bad Version\"}", true, false));

        // Another app with no versions besides active (for "no versions" scenario we keep none)
        s.addApp(new App("empty-app", "{\"name\":\"Empty\"}", 0));
    }

    // --------------------------- Handlers ---------------------------

    static final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{" +
                    "\"name\":\"AppVersionManagementServer\"," +
                    "\"workItem\":104470," +
                    "\"endpoints\":[\"GET /api/apps/{appId}/versions\",\"POST /api/apps/{appId}/versions/{versionNumber}/restore\"]" +
                    "}";
            writeJson(ex, 200, body);
        }
    }

    static final class AuditHandler implements HttpHandler {
        private final Store store;

        AuditHandler(Store store) {
            this.store = store;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "METHOD_NOT_ALLOWED", "Only GET is supported");
                return;
            }
            List<Map<String, Object>> data = new ArrayList<>();
            for (AuditEntry e : store.listAudit()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("auditId", e.auditId);
                m.put("appId", e.appId);
                m.put("restoredVersion", e.restoredVersion);
                m.put("user", e.user);
                m.put("timestamp", e.timestamp.toString());
                m.put("status", e.status);
                if (e.failureReason != null) m.put("failureReason", e.failureReason);
                data.add(m);
            }
            ApiResponse resp = ApiResponse.ok("Audit entries", data);
            writeApiResponse(ex, 200, resp);
        }
    }

    static final class AppsHandler implements HttpHandler {
        private final Store store;

        AppsHandler(Store store) {
            this.store = store;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            // Expected patterns:
            // /api/apps/{appId}/versions
            // /api/apps/{appId}/versions/{versionNumber}/restore
            String path = ex.getRequestURI().getPath();
            // remove leading "/api/apps"
            String rest = path.substring("/api/apps".length());
            if (rest.isEmpty() || "/".equals(rest)) {
                sendError(ex, 404, "NOT_FOUND", "Unknown endpoint");
                return;
            }

            // Split, ignoring leading slash
            String[] parts = rest.startsWith("/") ? rest.substring(1).split("/") : rest.split("/");
            // parts[0]=appId
            if (parts.length < 2) {
                sendError(ex, 404, "NOT_FOUND", "Unknown endpoint");
                return;
            }

            String appId = parts[0];
            String second = parts[1];

            if (!"versions".equals(second)) {
                sendError(ex, 404, "NOT_FOUND", "Unknown endpoint");
                return;
            }

            if ("GET".equalsIgnoreCase(ex.getRequestMethod()) && parts.length == 2) {
                handleGetVersions(ex, appId);
                return;
            }

            if ("POST".equalsIgnoreCase(ex.getRequestMethod()) && parts.length == 4 && "restore".equals(parts[3])) {
                int version;
                try {
                    version = Integer.parseInt(parts[2]);
                } catch (NumberFormatException nfe) {
                    sendError(ex, 400, "INVALID_VERSION", "versionNumber must be an integer");
                    return;
                }
                handleRestore(ex, appId, version);
                return;
            }

            sendError(ex, 404, "NOT_FOUND", "Unknown endpoint");
        }

        private void handleGetVersions(HttpExchange ex, String appId) throws IOException {
            UserContext user = parseUser(ex.getRequestHeaders());
            if (!user.isAuthenticated()) {
                sendError(ex, 401, "UNAUTHORIZED", "Missing X-User header");
                return;
            }
            if (!user.hasAnyRole("APP_VIEWER", "ADMIN")) {
                sendError(ex, 403, "FORBIDDEN", "User is not permitted to view application versions");
                return;
            }

            App app = store.getApp(appId).orElse(null);
            if (app == null) {
                sendError(ex, 404, "APP_NOT_FOUND", "Application does not exist for appId=" + appId);
                return;
            }

            // Read lock for consistency while listing
            app.lock.readLock().lock();
            try {
                List<AppVersion> versions = store.listValidVersions(appId);
                List<Map<String, Object>> out = new ArrayList<>();
                for (AppVersion v : versions) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionNumber", v.versionNumber);
                    m.put("createdAt", v.createdAt.toString());
                    m.put("createdBy", v.createdBy);
                    m.put("active", v.versionNumber == app.activeVersion);
                    // "Any other supported metadata" - keep minimal, but include flags for transparency
                    m.put("deleted", v.deleted);
                    m.put("accessible", v.accessible);
                    out.add(m);
                }

                if (out.isEmpty()) {
                    ApiResponse resp = ApiResponse.ok("No versions available for application", List.of());
                    writeApiResponse(ex, 200, resp);
                } else {
                    ApiResponse resp = ApiResponse.ok("Versions retrieved", out);
                    writeApiResponse(ex, 200, resp);
                }
            } finally {
                app.lock.readLock().unlock();
            }
        }

        private void handleRestore(HttpExchange ex, String appId, int versionNumber) throws IOException {
            UserContext user = parseUser(ex.getRequestHeaders());
            if (!user.isAuthenticated()) {
                sendError(ex, 401, "UNAUTHORIZED", "Missing X-User header");
                return;
            }
            if (!user.hasAnyRole("APP_RESTORER", "ADMIN")) {
                sendError(ex, 403, "FORBIDDEN", "User is not permitted to restore application versions");
                return;
            }

            App app = store.getApp(appId).orElse(null);
            if (app == null) {
                // audit failed attempt
                store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", "APP_NOT_FOUND"));
                sendError(ex, 404, "APP_NOT_FOUND", "Application does not exist for appId=" + appId);
                return;
            }

            if (versionNumber <= 0) {
                store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", "INVALID_VERSION_NUMBER"));
                sendError(ex, 400, "INVALID_VERSION", "versionNumber must be a positive integer");
                return;
            }

            Optional<AppVersion> maybeVersion = store.getVersion(appId, versionNumber);
            if (maybeVersion.isEmpty()) {
                store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", "VERSION_NOT_FOUND"));
                sendError(ex, 404, "VERSION_NOT_FOUND", "Version does not exist for application");
                return;
            }

            AppVersion v = maybeVersion.get();

            // Relationship check (explicit in AC #6)
            if (!Objects.equals(v.appId, appId)) {
                store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", "INVALID_APP_VERSION_RELATION"));
                sendError(ex, 400, "INVALID_RELATION", "Invalid version/application relationship");
                return;
            }

            if (v.deleted || !v.accessible) {
                store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", "VERSION_INACCESSIBLE"));
                sendError(ex, 409, "VERSION_INACCESSIBLE", "Prevented restoration of an invalid or inaccessible version");
                return;
            }

            // Transactional restore: take write lock, snapshot current state, apply, rollback on failure.
            boolean restored = false;
            String failure = null;
            app.lock.writeLock().lock();
            try {
                // Conflict rule: if already active, treat as conflict (4xx) to satisfy "conflict" scenario.
                if (app.activeVersion == versionNumber) {
                    failure = "VERSION_ALREADY_ACTIVE";
                    sendError(ex, 409, "RESTORE_CONFLICT", "Requested version is already active");
                    return;
                }

                String prevConfig = app.activeConfigJson;
                int prevActive = app.activeVersion;

                try {
                    // Restore components/config consistently: here it's the whole config blob.
                    app.activeConfigJson = v.configJson;
                    app.activeVersion = v.versionNumber;

                    // Simulate an unexpected restoration failure if request includes header:
                    // X-Debug-Fail-Restore: true
                    // (Useful to show rollback property in a runnable demo.)
                    String debugFail = ex.getRequestHeaders().getFirst("X-Debug-Fail-Restore");
                    if (debugFail != null && debugFail.equalsIgnoreCase("true")) {
                        throw new RuntimeException("Simulated restore failure");
                    }

                    restored = true;
                } catch (Exception e) {
                    // Rollback
                    app.activeConfigJson = prevConfig;
                    app.activeVersion = prevActive;
                    failure = "RESTORE_FAILED: " + e.getMessage();
                    sendError(ex, 500, "RESTORE_FAILED", "Unexpected restoration failure");
                    return;
                }

            } finally {
                app.lock.writeLock().unlock();
                // Audit must record status whether success or failure (requirement).
                if (restored) {
                    store.addAudit(new AuditEntry(appId, versionNumber, user.username, "SUCCESS", null));
                } else if (failure != null) {
                    store.addAudit(new AuditEntry(appId, versionNumber, user.username, "FAILED", failure));
                }
            }

            Map<String, Object> respData = new LinkedHashMap<>();
            respData.put("appId", appId);
            respData.put("activeVersion", versionNumber);
            respData.put("message", "Application restored successfully");

            ApiResponse resp = ApiResponse.ok("Restore completed", respData);
            writeApiResponse(ex, 200, resp);
        }
    }

    // --------------------------- Utilities ---------------------------

    static UserContext parseUser(Headers headers) {
        String user = Optional.ofNullable(headers.getFirst("X-User")).orElse("").trim();
        String rolesHeader = Optional.ofNullable(headers.getFirst("X-Roles")).orElse("").trim();
        Set<String> roles = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

        if (!rolesHeader.isBlank()) {
            Arrays.stream(rolesHeader.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.toUpperCase(Locale.ROOT))
                    .forEach(roles::add);
        }

        return new UserContext(user, roles);
    }

    static void sendError(HttpExchange ex, int status, String code, String details) throws IOException {
        ErrorDetails err = new ErrorDetails(status, code, details);
        ApiResponse resp = ApiResponse.fail("Request failed", err);
        writeApiResponse(ex, status, resp);
    }

    static void writeApiResponse(HttpExchange ex, int status, ApiResponse resp) throws IOException {
        String json = toJson(resp);
        writeJson(ex, status, json);
    }

    static void writeJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Minimal JSON writer (no dependencies). Handles Maps/Lists/Strings/Numbers/booleans/null and simple POJOs in this file.
    static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return '"' + escapeJson(s) + '"';
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Instant i) return '"' + i.toString() + '"';
        if (o instanceof OffsetDateTime odt) return '"' + odt.toString() + '"';
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(Objects.toString(e.getKey())));
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
            for (Object e : list) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(e));
            }
            sb.append(']');
            return sb.toString();
        }

        // Serialize known objects
        if (o instanceof ApiResponse r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", r.success);
            m.put("message", r.message);
            m.put("data", r.data);
            m.put("timestamp", r.timestamp.toString());
            if (r.error != null) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("status", r.error.status);
                em.put("code", r.error.code);
                em.put("details", r.error.details);
                m.put("error", em);
            }
            return toJson(m);
        }

        // Fallback to string
        return '"' + escapeJson(o.toString()) + '"';
    }

    static String escapeJson(String s) {
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
