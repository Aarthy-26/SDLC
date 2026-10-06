package com.aava.datapowerai.controller;

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.service.AuditService;
import com.aava.datapowerai.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Story ID: 104470
 * Title: App Version Management / Version History – Backend
 *
 * Implements backend APIs to:
 * - Retrieve application version history
 * - Restore an application to a selected previous version
 *
 * Repo context reuse:
 * - ApiResponse wrapper (com.aava.datapowerai.dto.common.ApiResponse)
 * - AuditService for audit logging
 * - UserService (/me logic) for current user resolution
 * - Spring Boot 3 + JdbcTemplate persistence style + @PreAuthorize method security
 *
 * ASSUMPTION: The real project does not yet contain app/version tables and repositories.
 * This file includes JdbcTemplate repositories and SQL DDL (as constants) to support the feature.
 * ASSUMPTION: "Application" corresponds to a row in dpai.apps (UUID id) and its active config lives in dpai.apps.config_json.
 * ASSUMPTION: Version snapshots are stored in dpai.app_versions keyed by (app_id, version_number) with config_json snapshot.
 * ASSUMPTION: Authorization model: any authenticated user may list versions for now; restore requires admin role.
 * If the real project has app-level ACLs, replace canAccessApp/canRestoreApp accordingly.
 */
class AppVersionHistoryBackend {

    // --- SQL/DDL to support this feature (place in migration tooling as appropriate) ---
    // ASSUMPTION: not found in repo content
    static final String DDL = """
            -- ASSUMPTION DDL for app versioning
            CREATE SCHEMA IF NOT EXISTS dpai;

            CREATE TABLE IF NOT EXISTS dpai.apps (
                id UUID PRIMARY KEY,
                name TEXT NOT NULL,
                config_json TEXT NOT NULL,
                active_version_number INT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE TABLE IF NOT EXISTS dpai.app_versions (
                id UUID PRIMARY KEY,
                app_id UUID NOT NULL REFERENCES dpai.apps(id) ON DELETE CASCADE,
                version_number INT NOT NULL,
                config_json TEXT NOT NULL,
                created_by TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                deleted BOOLEAN NOT NULL DEFAULT FALSE,
                accessible BOOLEAN NOT NULL DEFAULT TRUE,
                CONSTRAINT uq_app_version UNIQUE (app_id, version_number)
            );

            -- Optional: to protect concurrent restores at app-level
            CREATE INDEX IF NOT EXISTS ix_app_versions_app_id_created_at ON dpai.app_versions(app_id, created_at DESC);
            """;

    // --- Controller ---
    @RestController
    @RequestMapping("/api/apps")
    @Tag(name = "App Version Management", description = "Application version history and restore")
    static class AppVersionController {

        private final AppVersionService appVersionService;

        AppVersionController(AppVersionService appVersionService) {
            this.appVersionService = appVersionService;
        }

        @GetMapping("/{appId}/versions")
        @PreAuthorize("isAuthenticated()")
        @Operation(summary = "Get application versions")
        public ResponseEntity<ApiResponse<List<AppVersionMetadata>>> getVersions(@PathVariable("appId") UUID appId) {
            List<AppVersionMetadata> versions = appVersionService.getApplicationVersions(appId);
            // Requirement: return an appropriate response when no versions are available.
            if (versions.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success("No versions available for the application", versions));
            }
            return ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", versions));
        }

        @PostMapping("/{appId}/versions/{versionNumber}/restore")
        @PreAuthorize("isAuthenticated()")
        @Operation(summary = "Restore an application to a specific version")
        public ResponseEntity<ApiResponse<RestoreResult>> restore(
                @PathVariable("appId") UUID appId,
                @PathVariable("versionNumber") int versionNumber) {
            RestoreResult result = appVersionService.restoreApplicationVersion(appId, versionNumber);
            return ResponseEntity.ok(ApiResponse.success("Application restored successfully", result));
        }
    }

    // --- Service ---
    @Service
    static class AppVersionService {
        private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

        private final AppRepository appRepository;
        private final AppVersionRepository appVersionRepository;
        private final UserService userService;
        private final AuditService auditService;

        AppVersionService(AppRepository appRepository,
                          AppVersionRepository appVersionRepository,
                          UserService userService,
                          AuditService auditService) {
            this.appRepository = appRepository;
            this.appVersionRepository = appVersionRepository;
            this.userService = userService;
            this.auditService = auditService;
        }

        public List<AppVersionMetadata> getApplicationVersions(UUID appId) {
            // Validate app exists
            AppRow app = appRepository.findById(appId).orElseThrow(() -> new NotFoundException("Application not found"));

            // Authorization: requirement says only authorized users may retrieve.
            if (!canAccessApp(app)) {
                throw new ForbiddenException("Forbidden — not authorized to access this application");
            }

            List<AppVersionRow> rows = appVersionRepository.findValidByAppIdLatestFirst(appId);

            Integer activeVersion = app.activeVersionNumber();
            List<AppVersionMetadata> result = new ArrayList<>(rows.size());
            for (AppVersionRow r : rows) {
                boolean isActive = activeVersion != null && Objects.equals(activeVersion, r.versionNumber());
                result.add(new AppVersionMetadata(
                        r.versionNumber(),
                        r.createdAt(),
                        r.createdBy(),
                        isActive,
                        Map.of() // Any other supported metadata: none defined in story
                ));
            }
            return result;
        }

        /**
         * Transactional restore:
         * - Locks the app row to prevent concurrent restores
         * - Reads version snapshot
         * - Updates app config and active_version_number
         * - Writes audit log
         */
        @Transactional
        public RestoreResult restoreApplicationVersion(UUID appId, int versionNumber) {
            // Validate app exists and lock for update (concurrency)
            AppRow app = appRepository.findByIdForUpdate(appId)
                    .orElseThrow(() -> new NotFoundException("Application not found"));

            if (!canRestoreApp(app)) {
                // Requirement: 401/403
                throw new ForbiddenException("Forbidden — not authorized to restore this application");
            }

            // Validate version exists for app
            AppVersionRow version = appVersionRepository.findValidByAppIdAndVersionNumber(appId, versionNumber)
                    .orElseThrow(() -> new NotFoundException("Version not found"));

            // Validate relationship (already enforced by query)
            if (!Objects.equals(version.appId(), appId)) {
                throw new BadRequestException("Invalid version/application relationship");
            }

            String actor = userService.getCurrentUser().map(UserModel::getUsername).orElse("UNKNOWN");
            OffsetDateTime restoreTs = OffsetDateTime.now();

            try {
                // Restore: set current config_json to the snapshot
                // Requirement: Ensure components/configurations restored consistently.
                // ASSUMPTION: all configuration is contained in config_json.
                appRepository.updateConfigAndActiveVersion(appId, version.configJson(), versionNumber);

                auditService.logAction("APP_VERSION_RESTORE", "appId=" + appId + ", version=" + versionNumber + ", user=" + actor);

                return new RestoreResult(appId, versionNumber, restoreTs, actor);
            } catch (DataAccessException dae) {
                log.error("Restore failed due to DB error. appId={} version={}", appId, versionNumber, dae);
                // Transaction will roll back automatically due to runtime exception.
                auditService.logAction("APP_VERSION_RESTORE_FAILED", "appId=" + appId + ", version=" + versionNumber + ", user=" + actor + ", reason=" + dae.getMessage());
                throw new ServerException("Database/server failure during restore");
            } catch (RuntimeException ex) {
                log.error("Unexpected restoration failure. appId={} version={}", appId, versionNumber, ex);
                auditService.logAction("APP_VERSION_RESTORE_FAILED", "appId=" + appId + ", version=" + versionNumber + ", user=" + actor + ", reason=" + ex.getMessage());
                throw ex;
            }
        }

        // --- Authorization helpers ---

        // ASSUMPTION: app-level authorization not found in repo content.
        private boolean canAccessApp(AppRow app) {
            // Replace with app/project membership checks when available.
            return userService.getCurrentUser().isPresent();
        }

        // ASSUMPTION: restore requires elevated permission; using admin roles consistent with repo.
        private boolean canRestoreApp(AppRow app) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return false;
            return auth.getAuthorities().stream().anyMatch(a -> {
                String r = a.getAuthority();
                return "ROLE_TOOL_ADMIN".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_ADMIN".equals(r);
            });
        }
    }

    // --- Repositories (JdbcTemplate style consistent with repo) ---

    // ASSUMPTION: not found in repo content
    @Repository
    static class AppRepository {
        private final JdbcTemplate jdbcTemplate;

        AppRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        Optional<AppRow> findById(UUID appId) {
            String sql = "SELECT id, name, config_json, active_version_number, updated_at FROM dpai.apps WHERE id = ?";
            List<AppRow> list = jdbcTemplate.query(sql, (rs, rn) -> new AppRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("config_json"),
                    (Integer) rs.getObject("active_version_number"),
                    rs.getObject("updated_at", OffsetDateTime.class)
            ), appId);
            return list.stream().findFirst();
        }

        Optional<AppRow> findByIdForUpdate(UUID appId) {
            // Postgres row-level lock for safe concurrent restores
            String sql = "SELECT id, name, config_json, active_version_number, updated_at FROM dpai.apps WHERE id = ? FOR UPDATE";
            List<AppRow> list = jdbcTemplate.query(sql, (rs, rn) -> new AppRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("config_json"),
                    (Integer) rs.getObject("active_version_number"),
                    rs.getObject("updated_at", OffsetDateTime.class)
            ), appId);
            return list.stream().findFirst();
        }

        void updateConfigAndActiveVersion(UUID appId, String configJson, int activeVersionNumber) {
            String sql = """
                    UPDATE dpai.apps
                       SET config_json = ?,
                           active_version_number = ?,
                           updated_at = now()
                     WHERE id = ?
                    """;
            int updated = jdbcTemplate.update(sql, configJson, activeVersionNumber, appId);
            if (updated == 0) {
                throw new NotFoundException("Application not found");
            }
        }
    }

    // ASSUMPTION: not found in repo content
    @Repository
    static class AppVersionRepository {
        private final JdbcTemplate jdbcTemplate;

        AppVersionRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        List<AppVersionRow> findValidByAppIdLatestFirst(UUID appId) {
            // Exclude invalid/deleted/inaccessible versions based on business rules.
            String sql = """
                    SELECT id, app_id, version_number, config_json, created_by, created_at, deleted, accessible
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND deleted = FALSE
                       AND accessible = TRUE
                     ORDER BY version_number DESC, created_at DESC
                    """;
            return jdbcTemplate.query(sql, (rs, rn) -> new AppVersionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("app_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getString("config_json"),
                    rs.getString("created_by"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getBoolean("deleted"),
                    rs.getBoolean("accessible")
            ), appId);
        }

        Optional<AppVersionRow> findValidByAppIdAndVersionNumber(UUID appId, int versionNumber) {
            String sql = """
                    SELECT id, app_id, version_number, config_json, created_by, created_at, deleted, accessible
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND version_number = ?
                       AND deleted = FALSE
                       AND accessible = TRUE
                     LIMIT 1
                    """;
            List<AppVersionRow> list = jdbcTemplate.query(sql, (rs, rn) -> new AppVersionRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("app_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getString("config_json"),
                    rs.getString("created_by"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getBoolean("deleted"),
                    rs.getBoolean("accessible")
            ), appId, versionNumber);
            return list.stream().findFirst();
        }
    }

    // --- DTOs / Models ---

    static record AppVersionMetadata(
            int versionNumber,
            OffsetDateTime createdAt,
            String createdBy,
            boolean active,
            Map<String, Object> metadata
    ) {}

    static record RestoreResult(
            UUID appId,
            int restoredVersionNumber,
            OffsetDateTime restoredAt,
            String restoredBy
    ) {}

    // ASSUMPTION: not found in repo content
    static record AppRow(
            UUID id,
            String name,
            String configJson,
            Integer activeVersionNumber,
            OffsetDateTime updatedAt
    ) {}

    // ASSUMPTION: not found in repo content
    static record AppVersionRow(
            UUID id,
            UUID appId,
            int versionNumber,
            String configJson,
            String createdBy,
            OffsetDateTime createdAt,
            boolean deleted,
            boolean accessible
    ) {}

    // --- Exceptions (mapped by GlobalExceptionHandler to 500; controller uses runtime exceptions) ---
    // ASSUMPTION: repo only has GlobalExceptionHandler(Exception -> 500). For required 4xx codes,
    // we use ResponseStatusException-like semantics via these exceptions annotated with @ResponseStatus.

    @ResponseStatus(code = org.springframework.http.HttpStatus.NOT_FOUND)
    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    @ResponseStatus(code = org.springframework.http.HttpStatus.FORBIDDEN)
    static class ForbiddenException extends RuntimeException {
        ForbiddenException(String message) { super(message); }
    }

    @ResponseStatus(code = org.springframework.http.HttpStatus.BAD_REQUEST)
    static class BadRequestException extends RuntimeException {
        BadRequestException(String message) { super(message); }
    }

    @ResponseStatus(code = org.springframework.http.HttpStatus.CONFLICT)
    static class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }

    @ResponseStatus(code = org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
    static class ServerException extends RuntimeException {
        ServerException(String message) { super(message); }
    }
}
