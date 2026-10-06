/*
 * Story ID: 104470
 * Title: App Version Management / Version History – Backend
 *
 * Implements backend APIs for application version management:
 *  - GET  /api/apps/{appId}/versions
 *  - POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * Uses the repository conventions observed in datapowerai-backend:
 *  - Spring Boot (spring-boot-starter-web/security/jdbc)
 *  - JdbcTemplate repositories
 *  - Unified response wrapper: com.aava.datapowerai.dto.common.ApiResponse
 *  - Method security via @PreAuthorize
 *  - Audit logging via com.aava.datapowerai.service.AuditService
 *
 * ASSUMPTIONS (not found in repo content):
 *  - There is an "application" concept stored in DB tables under schema dpai.
 *  - Version snapshots are stored as JSON config blobs.
 *  - Authorization model: authenticated users can list versions; only admin roles can restore.
 *    (Repo shows admin role checks in controllers; no app-specific permission model found.)
 *  - GlobalExceptionHandler exists but only handles generic Exception; this file returns
 *    consistent 4xx responses where required.
 *
 * Database (DDL) assumptions used by JdbcTemplate queries in this file:
 *
 *  -- Applications table
 *  CREATE TABLE IF NOT EXISTS dpai.apps (
 *    id UUID PRIMARY KEY,
 *    name TEXT NOT NULL,
 *    active_version_number INT,
 *    updated_at TIMESTAMPTZ DEFAULT now()
 *  );
 *
 *  -- Version snapshots
 *  CREATE TABLE IF NOT EXISTS dpai.app_versions (
 *    id UUID PRIMARY KEY,
 *    app_id UUID NOT NULL REFERENCES dpai.apps(id) ON DELETE CASCADE,
 *    version_number INT NOT NULL,
 *    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 *    created_by UUID,
 *    created_by_username TEXT,
 *    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 *    is_accessible BOOLEAN NOT NULL DEFAULT TRUE,
 *    config_json TEXT NOT NULL,
 *    UNIQUE(app_id, version_number)
 *  );
 *
 *  -- Current app configuration (materialized)
 *  CREATE TABLE IF NOT EXISTS dpai.app_configs (
 *    app_id UUID PRIMARY KEY REFERENCES dpai.apps(id) ON DELETE CASCADE,
 *    config_json TEXT NOT NULL,
 *    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
 *  );
 */

package com.aava.datapowerai.controller;

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.service.AuditService;
import com.aava.datapowerai.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.*;

@SuppressWarnings("unused")
class AppVersionManagementBackend {

    // -------------------- Controller --------------------

    @RestController
    @RequestMapping("/api/apps")
    static class AppVersionController {

        private final AppVersionService appVersionService;

        AppVersionController(AppVersionService appVersionService) {
            this.appVersionService = appVersionService;
        }

        @GetMapping("/{appId}/versions")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> getAppVersions(@PathVariable("appId") UUID appId, Principal principal) {
            return appVersionService.getVersions(appId, principal)
                    .map(resp -> ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", resp)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiResponse.failure("Application not found")));
        }

        @PostMapping("/{appId}/versions/{versionNumber}/restore")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> restore(
                @PathVariable("appId") UUID appId,
                @PathVariable("versionNumber") int versionNumber,
                Principal principal,
                Authentication authentication) {

            AppVersionService.RestoreResult result = appVersionService.restoreVersion(appId, versionNumber, principal, authentication);

            if (result.httpStatus == HttpStatus.OK) {
                return ResponseEntity.ok(ApiResponse.success(result.message, result.data));
            }
            return ResponseEntity.status(result.httpStatus).body(ApiResponse.failure(result.message, result.data));
        }
    }

    // -------------------- Service --------------------

    @org.springframework.stereotype.Service
    static class AppVersionService {
        private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

        private final AppRepository appRepository;
        private final AppVersionRepository appVersionRepository;
        private final AppConfigRepository appConfigRepository;
        private final UserService userService;
        private final AuditService auditService;
        private final PlatformTransactionManager txManager;

        AppVersionService(AppRepository appRepository,
                          AppVersionRepository appVersionRepository,
                          AppConfigRepository appConfigRepository,
                          UserService userService,
                          AuditService auditService,
                          PlatformTransactionManager txManager) {
            this.appRepository = appRepository;
            this.appVersionRepository = appVersionRepository;
            this.appConfigRepository = appConfigRepository;
            this.userService = userService;
            this.auditService = auditService;
            this.txManager = txManager;
        }

        Optional<GetVersionsResponse> getVersions(UUID appId, Principal principal) {
            // validate app exists
            Optional<AppModel> app = appRepository.findById(appId);
            if (app.isEmpty()) return Optional.empty();

            // ASSUMPTION: authorization for listing is currently just authentication.
            List<AppVersionModel> versions = appVersionRepository.findAllValidByAppId(appId);

            List<VersionMetadata> items = new ArrayList<>(versions.size());
            for (AppVersionModel v : versions) {
                boolean active = app.get().activeVersionNumber() != null && Objects.equals(app.get().activeVersionNumber(), v.versionNumber());
                items.add(new VersionMetadata(
                        v.versionNumber(),
                        v.createdAt(),
                        v.createdByUsername(),
                        active,
                        Map.of("accessible", v.accessible())
                ));
            }

            return Optional.of(new GetVersionsResponse(appId, app.get().name(), items));
        }

        RestoreResult restoreVersion(UUID appId, int versionNumber, Principal principal, Authentication authentication) {
            if (versionNumber <= 0) {
                return RestoreResult.of(HttpStatus.BAD_REQUEST, "Invalid version number", null);
            }

            Optional<AppModel> app = appRepository.findById(appId);
            if (app.isEmpty()) {
                auditRestore(appId, versionNumber, "FAILED", "Application not found", principal);
                return RestoreResult.of(HttpStatus.NOT_FOUND, "Application not found", null);
            }

            if (!hasRestorePermission(authentication)) {
                auditRestore(appId, versionNumber, "DENIED", "User not authorized to restore", principal);
                return RestoreResult.of(HttpStatus.FORBIDDEN, "Forbidden — insufficient permissions to restore", null);
            }

            Optional<AppVersionModel> version = appVersionRepository.findValidByAppIdAndVersionNumber(appId, versionNumber);
            if (version.isEmpty()) {
                auditRestore(appId, versionNumber, "FAILED", "Version not found", principal);
                return RestoreResult.of(HttpStatus.NOT_FOUND, "Version not found", null);
            }

            // Prevent restoring current version (conflict) to satisfy "conflict" scenario.
            if (app.get().activeVersionNumber() != null && Objects.equals(app.get().activeVersionNumber(), versionNumber)) {
                auditRestore(appId, versionNumber, "FAILED", "Version already active", principal);
                return RestoreResult.of(HttpStatus.CONFLICT, "Restore conflict — requested version is already active", null);
            }

            // Transactional restore to avoid partial restore.
            DefaultTransactionDefinition def = new DefaultTransactionDefinition();
            def.setName("restore-app-version");
            def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionStatus tx = txManager.getTransaction(def);

            try {
                // Lock app row to avoid concurrent restores.
                Optional<AppModel> lockedApp = appRepository.findByIdForUpdate(appId);
                if (lockedApp.isEmpty()) {
                    throw new IllegalStateException("Application disappeared during restore");
                }

                // Re-check version after lock for consistency.
                Optional<AppVersionModel> lockedVersion = appVersionRepository.findValidByAppIdAndVersionNumber(appId, versionNumber);
                if (lockedVersion.isEmpty()) {
                    return RestoreResult.of(HttpStatus.NOT_FOUND, "Version not found", null);
                }

                // Restore config blob and mark active version.
                appConfigRepository.upsertConfig(appId, lockedVersion.get().configJson());
                appRepository.updateActiveVersion(appId, versionNumber);

                txManager.commit(tx);

                auditRestore(appId, versionNumber, "SUCCESS", "Restored successfully", principal);

                RestoreResponse data = new RestoreResponse(appId, versionNumber, OffsetDateTime.now());
                return RestoreResult.of(HttpStatus.OK, "Application restored successfully", data);
            } catch (DataAccessException dae) {
                safeRollback(tx);
                log.error("Restore failed due to DB error: appId={} version={} err={}", appId, versionNumber, dae.getMessage(), dae);
                auditRestore(appId, versionNumber, "FAILED", "Database failure", principal);
                return RestoreResult.of(HttpStatus.INTERNAL_SERVER_ERROR, "Database/server failure during restore", null);
            } catch (Exception ex) {
                safeRollback(tx);
                log.error("Restore failed unexpectedly: appId={} version={} err={}", appId, versionNumber, ex.getMessage(), ex);
                auditRestore(appId, versionNumber, "FAILED", ex.getMessage(), principal);
                return RestoreResult.of(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected restoration failure", null);
            }
        }

        private void safeRollback(TransactionStatus tx) {
            try {
                if (!tx.isCompleted()) {
                    txManager.rollback(tx);
                }
            } catch (Exception ignored) {
                // keep minimal; repo doesn't enforce heavy error handling
            }
        }

        private void auditRestore(UUID appId, int versionNumber, String status, String reason, Principal principal) {
            // Reuse existing AuditService (writes to dpai.audit_details)
            String actor = Optional.ofNullable(principal)
                    .map(Principal::getName)
                    .orElseGet(() -> userService.getCurrentUser().map(UserModel::getUsername).orElse("UNKNOWN"));

            // Put all required audit info in targetName since AuditModel schema is fixed in repo.
            String target = "appId=" + appId + ",version=" + versionNumber + ",status=" + status + ",reason=" + (reason == null ? "" : reason);
            auditService.logAction("APP_VERSION_RESTORE", actor + " -> " + target);
        }

        private boolean hasRestorePermission(Authentication auth) {
            // ASSUMPTION: Use admin roles present in repo as permission to restore.
            if (auth == null || !auth.isAuthenticated()) return false;
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String r = ga.getAuthority();
                if ("ROLE_TOOL_ADMIN".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_ADMIN".equals(r)) {
                    return true;
                }
            }
            return false;
        }

        static final class RestoreResult {
            final HttpStatus httpStatus;
            final String message;
            final Object data;

            private RestoreResult(HttpStatus httpStatus, String message, Object data) {
                this.httpStatus = httpStatus;
                this.message = message;
                this.data = data;
            }

            static RestoreResult of(HttpStatus status, String message, Object data) {
                return new RestoreResult(status, message, data);
            }
        }
    }

    // -------------------- Repositories (JdbcTemplate) --------------------

    @org.springframework.stereotype.Repository
    static class AppRepository {
        private final JdbcTemplate jdbcTemplate;

        AppRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        Optional<AppModel> findById(UUID appId) {
            String sql = """
                    SELECT id, name, active_version_number
                      FROM dpai.apps
                     WHERE id = ?
                    """;
            List<AppModel> list = jdbcTemplate.query(sql, appRowMapper(), appId);
            return list.stream().findFirst();
        }

        Optional<AppModel> findByIdForUpdate(UUID appId) {
            String sql = """
                    SELECT id, name, active_version_number
                      FROM dpai.apps
                     WHERE id = ?
                     FOR UPDATE
                    """;
            List<AppModel> list = jdbcTemplate.query(sql, appRowMapper(), appId);
            return list.stream().findFirst();
        }

        void updateActiveVersion(UUID appId, int versionNumber) {
            String sql = """
                    UPDATE dpai.apps
                       SET active_version_number = ?,
                           updated_at = now()
                     WHERE id = ?
                    """;
            int updated = jdbcTemplate.update(sql, versionNumber, appId);
            if (updated != 1) {
                throw new IllegalStateException("Failed to update active version for appId=" + appId);
            }
        }

        private RowMapper<AppModel> appRowMapper() {
            return (rs, rn) -> new AppModel(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    (Integer) rs.getObject("active_version_number")
            );
        }
    }

    @org.springframework.stereotype.Repository
    static class AppVersionRepository {
        private final JdbcTemplate jdbcTemplate;

        AppVersionRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        List<AppVersionModel> findAllValidByAppId(UUID appId) {
            String sql = """
                    SELECT app_id, version_number, created_at, created_by_username, is_accessible, config_json
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND is_deleted = FALSE
                       AND is_accessible = TRUE
                     ORDER BY version_number DESC
                    """;
            return jdbcTemplate.query(sql, rowMapper(), appId);
        }

        Optional<AppVersionModel> findValidByAppIdAndVersionNumber(UUID appId, int versionNumber) {
            String sql = """
                    SELECT app_id, version_number, created_at, created_by_username, is_accessible, config_json
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND version_number = ?
                       AND is_deleted = FALSE
                       AND is_accessible = TRUE
                    """;
            List<AppVersionModel> list = jdbcTemplate.query(sql, rowMapper(), appId, versionNumber);
            return list.stream().findFirst();
        }

        private RowMapper<AppVersionModel> rowMapper() {
            return (rs, rn) -> new AppVersionModel(
                    rs.getObject("app_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("created_by_username"),
                    rs.getBoolean("is_accessible"),
                    rs.getString("config_json")
            );
        }
    }

    @org.springframework.stereotype.Repository
    static class AppConfigRepository {
        private final JdbcTemplate jdbcTemplate;

        AppConfigRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void upsertConfig(UUID appId, String configJson) {
            String sql = """
                    INSERT INTO dpai.app_configs (app_id, config_json, updated_at)
                    VALUES (?, ?, now())
                    ON CONFLICT (app_id) DO UPDATE
                      SET config_json = EXCLUDED.config_json,
                          updated_at = now()
                    """;
            jdbcTemplate.update(sql, appId, configJson);
        }
    }

    // -------------------- Models / DTOs --------------------

    // ASSUMPTION: not found in repo content
    static record AppModel(UUID id, String name, Integer activeVersionNumber) {}

    // ASSUMPTION: not found in repo content
    static record AppVersionModel(
            UUID appId,
            int versionNumber,
            OffsetDateTime createdAt,
            String createdByUsername,
            boolean accessible,
            String configJson
    ) {}

    static record VersionMetadata(
            int versionNumber,
            OffsetDateTime createdAt,
            String createdBy,
            boolean active,
            Map<String, Object> metadata
    ) {}

    static record GetVersionsResponse(
            UUID appId,
            String appName,
            List<VersionMetadata> versions
    ) {}

    static record RestoreResponse(
            UUID appId,
            int restoredVersionNumber,
            OffsetDateTime restoredAt
    ) {}
}
